package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.SplittableRandom;

/**
 * Helpers of the still encoder tests: synthetic and natural pictures as sample planes, decoding
 * with the module's decoder, and comparison with the encoder's reconstruction.
 */
public final class StillTesting {
    private StillTesting() {
    }

    /** A picture as sample planes. */
    public record Planes(int width, int height, int layout, int bitDepth, short[][] data, int[] strides) {
        public int planeCount() {
            return layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        }

        public int planeWidth(int p) {
            var ss = p == 0 ? 0 : layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
            return (width + ss) >> ss;
        }

        public int planeHeight(int p) {
            var ss = p == 0 ? 0 : layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            return (height + ss) >> ss;
        }
    }

    /** Returns the chroma format of a layout. */
    public static ChromaFormat format(int layout) {
        return switch (layout) {
            case SequenceHeader.LAYOUT_I400 -> ChromaFormat.MONOCHROME;
            case SequenceHeader.LAYOUT_I420 -> ChromaFormat.YUV420;
            case SequenceHeader.LAYOUT_I422 -> ChromaFormat.YUV422;
            default -> ChromaFormat.YUV444;
        };
    }

    /** Reads a frame of the cached foreman CIF clip as 4:2:0 planes, or a synthetic picture when absent. */
    public static Planes foreman(int frame) {
        var file = Path.of(System.getProperty("user.home"), ".smalt", "cache", "clips", "foreman.yuv");
        if (!Files.isRegularFile(file)) {
            return synthetic(352, 288, SequenceHeader.LAYOUT_I420, 8, 7);
        }
        try (var in = Files.newInputStream(file)) {
            var frameSize = 352 * 288 * 3 / 2;
            in.skipNBytes((long) frame * frameSize);
            var bytes = in.readNBytes(frameSize);
            var y = new short[352 * 288];
            var u = new short[176 * 144];
            var v = new short[176 * 144];
            for (var i = 0; i < y.length; i++) {
                y[i] = (short) (bytes[i] & 0xFF);
            }
            for (var i = 0; i < u.length; i++) {
                u[i] = (short) (bytes[y.length + i] & 0xFF);
                v[i] = (short) (bytes[y.length + u.length + i] & 0xFF);
            }
            return new Planes(352, 288, SequenceHeader.LAYOUT_I420, 8, new short[][]{y, u, v}, new int[]{352, 176, 176});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds a synthetic picture: smooth gradients, edges, text-like strokes and noise. */
    public static Planes synthetic(int width, int height, int layout, int bitDepth, long seed) {
        var random = new SplittableRandom(seed);
        var max = (1 << bitDepth) - 1;
        var planes = layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var data = new short[3][];
        var strides = new int[3];
        var probe = new Planes(width, height, layout, bitDepth, data, strides);
        for (var p = 0; p < planes; p++) {
            var w = probe.planeWidth(p);
            var h = probe.planeHeight(p);
            var plane = new short[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    double v = 0.5 + 0.3 * Math.sin((x + p * 7) * 0.05) * Math.cos(y * 0.07);
                    if (((x / 9) + (y / 13)) % 5 == 0) {
                        v += 0.25;
                    }
                    if ((x * 3 + y * 2) % 37 < 3) {
                        v -= 0.3;
                    }
                    v += (random.nextDouble() - 0.5) * 0.06;
                    plane[y * w + x] = (short) Math.clamp(Math.round(v * max), 0, max);
                }
            }
            data[p] = plane;
            strides[p] = w;
        }
        return probe;
    }

    /** Builds a screen-content picture: flat rectangles, one-sample lines and text-like glyphs in a few colors. */
    public static Planes screen(int width, int height, int layout, int bitDepth, long seed) {
        var random = new SplittableRandom(seed);
        var rgbColors = new int[][]{{255, 255, 255}, {30, 30, 30}, {0, 120, 215}, {230, 60, 40}, {240, 240, 240},
                {20, 160, 80}};
        var yuv = new int[rgbColors.length][3];
        for (var i = 0; i < rgbColors.length; i++) {
            var r = rgbColors[i][0];
            var g = rgbColors[i][1];
            var b = rgbColors[i][2];
            yuv[i][0] = (int) Math.round(16 + 0.183 * r + 0.614 * g + 0.062 * b);
            yuv[i][1] = (int) Math.round(128 - 0.101 * r - 0.339 * g + 0.439 * b);
            yuv[i][2] = (int) Math.round(128 + 0.439 * r - 0.399 * g - 0.040 * b);
        }
        var index = new int[width * height];
        for (var k = 0; k < 40; k++) {
            var x0 = random.nextInt(width);
            var y0 = random.nextInt(height);
            var w = 4 + random.nextInt(Math.max(1, width / 3));
            var h = 2 + random.nextInt(Math.max(1, height / 4));
            var c = random.nextInt(rgbColors.length);
            for (var y = y0; y < Math.min(height, y0 + h); y++) {
                for (var x = x0; x < Math.min(width, x0 + w); x++) {
                    index[y * width + x] = c;
                }
            }
        }
        for (var y = 2; y + 7 < height; y += 11) {
            for (var x = 2; x + 5 < width; x += 7) {
                if (random.nextInt(3) == 0) {
                    continue;
                }
                var glyph = random.nextLong();
                for (var gy = 0; gy < 7; gy++) {
                    for (var gx = 0; gx < 5; gx++) {
                        if (((glyph >>> (gy * 5 + gx)) & 1) != 0) {
                            index[(y + gy) * width + x + gx] = 1;
                        }
                    }
                }
            }
        }
        var data = new short[3][];
        var strides = new int[3];
        var out = new Planes(width, height, layout, bitDepth, data, strides);
        var shift = bitDepth - 8;
        for (var p = 0; p < out.planeCount(); p++) {
            var w = out.planeWidth(p);
            var h = out.planeHeight(p);
            var plane = new short[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var sx = Math.min(width - 1, x * width / w);
                    var sy = Math.min(height - 1, y * height / h);
                    plane[y * w + x] = (short) (yuv[index[sy * width + sx]][p] << shift);
                }
            }
            data[p] = plane;
            strides[p] = w;
        }
        return out;
    }

    /** Converts planes of another layout or depth from a 4:2:0 8-bit picture by replication and scaling. */
    public static Planes convert(Planes src, int layout, int bitDepth) {
        var data = new short[3][];
        var strides = new int[3];
        var out = new Planes(src.width(), src.height(), layout, bitDepth, data, strides);
        var shift = bitDepth - src.bitDepth();
        for (var p = 0; p < out.planeCount(); p++) {
            var w = out.planeWidth(p);
            var h = out.planeHeight(p);
            var plane = new short[w * h];
            var sw = src.planeWidth(p);
            var sh = src.planeHeight(p);
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var sx = Math.min(sw - 1, x * sw / w);
                    var sy = Math.min(sh - 1, y * sh / h);
                    var v = src.data()[p][sy * src.strides()[p] + sx];
                    plane[y * w + x] = (short) (shift >= 0 ? (v << shift) | (v >> (8 - shift)) : v >> -shift);
                }
            }
            data[p] = plane;
            strides[p] = w;
        }
        return out;
    }

    /** Prepends a temporal delimiter to a still's OBUs, giving a temporal unit. */
    public static byte[] temporalUnit(byte[] obus) {
        var out = new ByteArrayOutputStream(obus.length + 2);
        out.write(0x12);
        out.write(0);
        out.writeBytes(obus);
        return out.toByteArray();
    }

    /** Decodes a still's OBUs with the module's decoder at its bit depth. */
    public static Picture decode(byte[] obus, int width, int height) {
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.MICROSECONDS).width(width)
                .height(height).build();
        var out = new ArrayList<Picture>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).threads(1).build())) {
            decoder.decode(new Packet(1, temporalUnit(obus), 0, 0, 1, true), frame -> out.add(copy(frame.picture())));
        }
        if (out.size() != 1) {
            throw new AssertionError("decoded " + out.size() + " frames");
        }
        return out.getFirst();
    }

    private static Picture copy(Picture p) {
        if (p instanceof Yuv16Picture w) {
            var c = Yuv16Picture.allocate(w.width(), w.height(), w.chromaFormat(), w.bitDepth(), false, w.color());
            System.arraycopy(w.y(), 0, c.y(), 0, w.y().length);
            if (w.u() != null) {
                System.arraycopy(w.u(), 0, c.u(), 0, w.u().length);
                System.arraycopy(w.v(), 0, c.v(), 0, w.v().length);
            }
            return c;
        }
        var y = (YuvPicture) p;
        var c = YuvPicture.allocate(y.width(), y.height(), y.chromaFormat(), false, y.color());
        System.arraycopy(y.y(), 0, c.y(), 0, y.y().length);
        if (y.u() != null) {
            System.arraycopy(y.u(), 0, c.u(), 0, y.u().length);
            System.arraycopy(y.v(), 0, c.v(), 0, y.v().length);
        }
        return c;
    }

    /** Returns the sample of a decoded picture. */
    public static int sample(Picture p, int plane, int x, int y) {
        if (p instanceof Yuv16Picture w) {
            var d = plane == 0 ? w.y() : plane == 1 ? w.u() : w.v();
            var s = plane == 0 ? w.yStride() : w.uvStride();
            return d[y * s + x];
        }
        var b = (YuvPicture) p;
        var d = plane == 0 ? b.y() : plane == 1 ? b.u() : b.v();
        var s = plane == 0 ? b.yStride() : b.uvStride();
        return d[y * s + x] & 0xFF;
    }

    /** Returns a description of the first sample where a decoded picture differs from a reconstruction, or null. */
    public static String firstDifference(Picture decoded, Av1Picture recon,
                                         Planes geometry) {
        for (var p = 0; p < geometry.planeCount(); p++) {
            var w = geometry.planeWidth(p);
            var h = geometry.planeHeight(p);
            var stride = recon.stride[p == 0 ? 0 : 1];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var d = sample(decoded, p, x, y);
                    var r = recon.data[p][y * stride + x];
                    if (d != r) {
                        return "plane " + p + " at " + x + "," + y + ": decoded " + d + ", reconstructed " + r;
                    }
                }
            }
        }
        return null;
    }

    /** Returns the PSNR of a decoded picture against source planes, over all planes. */
    public static double psnr(Planes source, Picture decoded) {
        long sse = 0;
        long n = 0;
        for (var p = 0; p < source.planeCount(); p++) {
            var w = source.planeWidth(p);
            var h = source.planeHeight(p);
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var d = source.data()[p][y * source.strides()[p] + x] - sample(decoded, p, x, y);
                    sse += (long) d * d;
                    n++;
                }
            }
        }
        var max = (1 << source.bitDepth()) - 1.0;
        return sse == 0 ? 99 : 10 * Math.log10(max * max * n / sse);
    }

    /** Encodes planes with the still encoder. */
    public static byte[] encode(Planes planes, StillSettings settings, StillFrameEncoder[] encoderOut) {
        var encoder = new StillFrameEncoder(planes.width(), planes.height(), planes.layout(), planes.bitDepth(),
                ColorInfo.BT709_LIMITED, settings, com.github.auties00.smalt.MediaLimits.DEFAULT);
        if (encoderOut != null) {
            encoderOut[0] = encoder;
        }
        return encoder.encode(planes.data(), planes.strides());
    }
}
