package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and reads transparent test clips for the alpha stream tests of the VP8 and VP9 modules: synthetic RGBA
 * frames whose alpha holds a moving disc with a soft edge over a horizontal ramp, the alpha planes Smalt and ffmpeg
 * decode from a transparent WebM file, and the conversions between them.
 */
public final class AlphaClips {
    /** The ffmpeg lavfi source of a transparent clip, with a {@code %d} width, height and frame count. */
    public static final String FFMPEG_SOURCE = "testsrc2=size=%dx%d:rate=10,format=yuva420p,"
            + "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='clip(3*X+60*sin(N/2+Y/9),0,255)',trim=end_frame=%d";

    private AlphaClips() {
        throw new AssertionError();
    }

    /**
     * Returns frame {@code index} of the synthetic clip: a color pattern that moves, a disc of opaque pixels with
     * a soft edge that moves the other way, a translucent ramp elsewhere and a fully transparent corner.
     */
    public static RgbaPicture frame(int index, int width, int height) {
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        var cx = width * (0.3 + 0.4 * ((index * 7) % 20) / 20.0);
        var cy = height / 2.0;
        var radius = Math.min(width, height) / 3.0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var offset = y * picture.stride() + 4 * x;
                pixels[offset] = (byte) ((x * 255 / Math.max(1, width - 1) + index * 9) & 0xFF);
                pixels[offset + 1] = (byte) ((y * 255 / Math.max(1, height - 1)) & 0xFF);
                pixels[offset + 2] = (byte) (((x + y) * 4 + index * 5) & 0xFF);
                var distance = Math.hypot(x - cx, y - cy);
                double alpha;
                if (x < width / 6 && y < height / 6) {
                    alpha = 0;
                } else if (distance < radius - 2) {
                    alpha = 255;
                } else if (distance < radius + 2) {
                    alpha = 255 * (radius + 2 - distance) / 4;
                } else {
                    alpha = 40 + 120.0 * x / width;
                }
                pixels[offset + 3] = (byte) Math.round(alpha);
            }
        }
        return picture;
    }

    /** Returns the alpha channel of an RGBA picture as a tight plane. */
    public static byte[] alpha(RgbaPicture picture) {
        var plane = new byte[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                plane[y * picture.width() + x] = picture.pixels()[y * picture.stride() + 4 * x + 3];
            }
        }
        return plane;
    }

    /** Returns the alpha plane of a decoded picture as a tight 8-bit plane, rounding deeper samples. */
    public static byte[] alpha(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var plane = new byte[width * height];
        switch (picture) {
            case YuvPicture yuv -> {
                for (var row = 0; row < height; row++) {
                    System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), plane, row * width, width);
                }
            }
            case Yuv16Picture yuv -> {
                var max = (1 << yuv.bitDepth()) - 1;
                for (var row = 0; row < height; row++) {
                    for (var x = 0; x < width; x++) {
                        plane[row * width + x] = (byte) Math.round((yuv.alpha()[row * yuv.alphaStride() + x] & 0xFFFF)
                                * 255.0 / max);
                    }
                }
            }
            case RgbaPicture rgba -> {
                return alpha(rgba);
            }
            default -> throw new IllegalArgumentException("no alpha plane in " + picture);
        }
        return plane;
    }

    /** Returns a decoded 8-bit picture as I420 bytes followed by its alpha plane, ffmpeg's {@code yuva420p}. */
    public static byte[] yuva420p(YuvPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        var out = new byte[2 * width * height + 2 * chromaWidth * chromaHeight];
        var position = 0;
        for (var row = 0; row < height; row++, position += width) {
            System.arraycopy(picture.y(), row * picture.yStride(), out, position, width);
        }
        for (var plane : new byte[][]{picture.u(), picture.v()}) {
            for (var row = 0; row < chromaHeight; row++, position += chromaWidth) {
                System.arraycopy(plane, row * picture.uvStride(), out, position, chromaWidth);
            }
        }
        for (var row = 0; row < height; row++, position += width) {
            System.arraycopy(picture.alpha(), row * picture.alphaStride(), out, position, width);
        }
        return out;
    }

    /** One decoded frame: its timestamp, its {@code yuva420p} bytes and its alpha plane. */
    public record Decoded(long pts, byte[] yuva, byte[] alpha) {
    }

    /** Decodes every frame of a file with {@link VideoReader}. */
    public static List<Decoded> read(Path file) {
        var frames = new ArrayList<Decoded>();
        try (var source = com.github.auties00.smalt.io.MediaSource.of(file); var reader = VideoReader.open(source)) {
            for (VideoFrame frame = reader.read(); frame != null; frame = reader.read()) {
                var yuv = (YuvPicture) frame.picture();
                frames.add(new Decoded(frame.pts(), yuva420p(yuv), alpha(yuv)));
            }
        }
        return frames;
    }

    /**
     * Decodes a transparent WebM file with ffmpeg through a libvpx decoder ({@code libvpx} or {@code libvpx-vp9}),
     * the only ffmpeg decoders that read the alpha stream, into {@code yuva420p} frames.
     */
    public static byte[] ffmpegYuva(Path file, String decoder) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-c:v", decoder, "-i",
                file.toString(), "-f", "rawvideo", "-pix_fmt", "yuva420p", "-fps_mode", "passthrough", "-"));
    }

    /** Returns the size of one {@code yuva420p} frame. */
    public static int yuvaFrameSize(int width, int height) {
        return 2 * width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
    }

    /** Returns the alpha plane of frame {@code index} of concatenated {@code yuva420p} frames. */
    public static byte[] yuvaAlpha(byte[] frames, int index, int width, int height) {
        var size = yuvaFrameSize(width, height);
        var start = index * size + size - width * height;
        return java.util.Arrays.copyOfRange(frames, start, start + width * height);
    }
}
