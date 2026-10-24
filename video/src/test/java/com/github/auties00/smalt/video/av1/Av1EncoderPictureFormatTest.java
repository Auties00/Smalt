package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the picture formats of the predicted usages: every chroma format AV1 codes at 8, 10 and
 * 12 bits, in real time and good quality. Each stream is decoded by the module's decoder, which
 * must reproduce the source closely, and by dav1d through ffmpeg, whose output must equal the
 * module's exactly, as AV1 reconstruction is normative.
 */
class Av1EncoderPictureFormatTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 100;

    private static final int HEIGHT = 68;

    private static final int FRAMES = 10;

    static Stream<Arguments> formats() {
        var out = new ArrayList<Arguments>();
        for (var usage : List.of(Av1EncoderUsage.REALTIME, Av1EncoderUsage.GOOD_QUALITY)) {
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                    ChromaFormat.MONOCHROME)) {
                for (var depth : List.of(8, 10, 12)) {
                    out.add(Arguments.of(usage, format, depth));
                }
            }
        }
        return out.stream();
    }

    static Av1EncoderOptions options(Av1EncoderUsage usage, ChromaFormat format, int depth) {
        var base = usage == Av1EncoderUsage.REALTIME
                ? Av1EncoderOptions.forRealtime().toBuilder().bitrate(600_000).dropFrames(false)
                : Av1EncoderOptions.ofQuality(60).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(8);
        return base.timeBase(TIME_BASE).pictureFormat(format, depth).build();
    }

    static Picture convert(YuvPicture picture, ChromaFormat format, int depth) {
        return depth == 8 ? ImageOps.toYuv(picture, format, ColorInfo.BT709_LIMITED)
                : ImageOps.toYuv16(picture, format, ColorInfo.BT709_LIMITED, depth);
    }

    static List<Packet> encode(Av1Encoder encoder, List<Picture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    static List<Picture> decode(Av1Encoder encoder, List<Packet> packets) {
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                .codecConfig(encoder.codecConfig().orElse(null)).width(encoder.width()).height(encoder.height())
                .build();
        var out = new ArrayList<Picture>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var p : packets) {
                decoder.decode(p, frame -> out.add(copy(frame.picture())));
            }
        }
        return out;
    }

    private static Picture copy(Picture p) {
        if (p instanceof YuvPicture yuv) {
            return yuv.toBuilder().luma(yuv.y().clone(), yuv.yStride())
                    .chroma(yuv.u() == null ? null : yuv.u().clone(), yuv.v() == null ? null : yuv.v().clone(),
                            yuv.uvStride()).build();
        }
        var yuv = (Yuv16Picture) p;
        return yuv.toBuilder().luma(yuv.y().clone(), yuv.yStride())
                .chroma(yuv.u() == null ? null : yuv.u().clone(), yuv.v() == null ? null : yuv.v().clone(),
                        yuv.uvStride()).build();
    }

    /** Returns the planes of a picture as little-endian 16-bit words, or bytes at 8 bits, as ffmpeg writes them. */
    static byte[] raw(Picture p) {
        var out = new ByteArrayOutputStream();
        var planes = chromaPlanes(p) ? 3 : 1;
        for (var pl = 0; pl < planes; pl++) {
            if (p instanceof YuvPicture yuv) {
                var w = pl == 0 ? yuv.width() : yuv.chromaWidth();
                var h = pl == 0 ? yuv.height() : yuv.chromaHeight();
                var d = pl == 0 ? yuv.y() : pl == 1 ? yuv.u() : yuv.v();
                var s = pl == 0 ? yuv.yStride() : yuv.uvStride();
                for (var y = 0; y < h; y++) {
                    out.write(d, y * s, w);
                }
            } else {
                var yuv = (Yuv16Picture) p;
                var w = pl == 0 ? yuv.width() : yuv.chromaWidth();
                var h = pl == 0 ? yuv.height() : yuv.chromaHeight();
                var d = pl == 0 ? yuv.y() : pl == 1 ? yuv.u() : yuv.v();
                var s = pl == 0 ? yuv.yStride() : yuv.uvStride();
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var v = d[y * s + x];
                        out.write(v & 0xFF);
                        out.write((v >> 8) & 0xFF);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    private static boolean chromaPlanes(Picture p) {
        var format = p instanceof YuvPicture yuv ? yuv.chromaFormat() : ((Yuv16Picture) p).chromaFormat();
        return format.hasChroma();
    }

    /** Returns the PSNR of two pictures of the same format at their own peak. */
    static double psnr(Picture a, Picture b, int depth) {
        var ra = samples(a);
        var rb = samples(b);
        assertEquals(ra.length, rb.length, "sample count");
        double sse = 0;
        for (var i = 0; i < ra.length; i++) {
            double d = ra[i] - rb[i];
            sse += d * d;
        }
        var peak = (1 << depth) - 1.0;
        return sse == 0 ? 99 : 10 * Math.log10(peak * peak * ra.length / sse);
    }

    private static int[] samples(Picture p) {
        var bytes = raw(p);
        if (p instanceof YuvPicture) {
            var out = new int[bytes.length];
            for (var i = 0; i < bytes.length; i++) {
                out[i] = bytes[i] & 0xFF;
            }
            return out;
        }
        var out = new int[bytes.length / 2];
        for (var i = 0; i < out.length; i++) {
            out[i] = (bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] & 0xFF) << 8;
        }
        return out;
    }

    static String pixelFormat(ChromaFormat format, int depth) {
        var base = switch (format) {
            case YUV420 -> "yuv420p";
            case YUV422 -> "yuv422p";
            case YUV444 -> "yuv444p";
            default -> "gray";
        };
        return depth == 8 ? base : base + depth + "le";
    }

    @ParameterizedTest(name = "{0} {1} {2}-bit")
    @MethodSource("formats")
    @DisplayName("codes the picture format and decodes to dav1d's output exactly")
    void codes(Av1EncoderUsage usage, ChromaFormat format, int depth) throws IOException {
        var sources = new ArrayList<Picture>();
        for (var p : Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES)) {
            sources.add(convert(p, format, depth));
        }
        List<Packet> packets;
        List<Picture> decoded;
        byte[] config;
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options(usage, format, depth))) {
            packets = encode(encoder, sources);
            config = encoder.codecConfig().orElseThrow();
            decoded = decode(encoder, packets);
        }
        assertEquals(FRAMES, packets.size(), "packets");
        assertEquals(FRAMES, decoded.size(), "decoded frames");
        // av1C: seq_profile, high_bitdepth, twelve_bit, monochrome, chroma subsampling
        var profile = format == ChromaFormat.YUV422 || depth == 12 ? 2 : format == ChromaFormat.YUV444 ? 1 : 0;
        assertEquals(profile, (config[1] & 0xFF) >> 5, "profile");
        assertEquals(depth > 8 ? 1 : 0, (config[2] >> 6) & 1, "high bit depth");
        assertEquals(depth == 12 ? 1 : 0, (config[2] >> 5) & 1, "twelve bit");
        assertEquals(format == ChromaFormat.MONOCHROME ? 1 : 0, (config[2] >> 4) & 1, "monochrome");
        assertEquals(format == ChromaFormat.YUV444 ? 0 : 1, (config[2] >> 3) & 1, "horizontal subsampling");
        assertEquals(format == ChromaFormat.YUV420 || format == ChromaFormat.MONOCHROME ? 1 : 0,
                (config[2] >> 2) & 1, "vertical subsampling");
        for (var i = 0; i < FRAMES; i++) {
            var picture = decoded.get(i);
            assertEquals(depth, picture instanceof Yuv16Picture yuv ? yuv.bitDepth() : 8, "frame " + i + " depth");
            var psnr = psnr(sources.get(i), picture, depth);
            assertTrue(psnr > 30, "frame " + i + " psnr " + psnr);
        }
        var file = Scratch.directory("av1-formats").resolve(usage + "-" + format + "-" + depth + ".ivf");
        var units = new ArrayList<byte[]>();
        for (var p : packets) {
            units.add(p.data());
        }
        Files.write(file, Av1EncTesting.ivf(WIDTH, HEIGHT, units));
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), pixelFormat(format, depth), 0);
        var ours = new ByteArrayOutputStream();
        for (var p : decoded) {
            ours.writeBytes(raw(p));
        }
        assertArrayEquals(reference, ours.toByteArray(), "dav1d output");
    }

    @Test
    @DisplayName("keeps more precision of a 10-bit source at 10 bits than at 8 bits")
    void tenBitPrecision() {
        // a smooth gradient with detail below one 8-bit step
        var sources = new ArrayList<Picture>();
        for (var f = 0; f < 6; f++) {
            var picture = Yuv16Picture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, 10, false,
                    ColorInfo.BT709_LIMITED);
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    picture.y()[y * picture.yStride() + x] = (short) (64 + (x * 7 + y * 3 + f * 2) % 876);
                }
            }
            sources.add(picture);
        }
        var psnr = new double[2];
        for (var depth = 8; depth <= 10; depth += 2) {
            var options = Av1EncoderOptions.ofQuality(98).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(4)
                    .timeBase(TIME_BASE).pictureFormat(ChromaFormat.YUV420, depth).build();
            try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
                var decoded = decode(encoder, encode(encoder, sources));
                double total = 0;
                for (var i = 0; i < decoded.size(); i++) {
                    var picture = decoded.get(i) instanceof YuvPicture yuv ? ImageOps.toBitDepth(yuv, 10)
                            : decoded.get(i);
                    total += psnr(sources.get(i), picture, 10);
                }
                psnr[(depth - 8) >> 1] = total / decoded.size();
            }
        }
        assertTrue(psnr[1] > psnr[0] + 3, "10-bit " + psnr[1] + " dB, 8-bit " + psnr[0] + " dB");
    }

    static Stream<Arguments> identityFormats() {
        var out = new ArrayList<Arguments>();
        for (var usage : Av1EncoderUsage.values()) {
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
                out.add(Arguments.of(usage, format));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("identityFormats")
    @DisplayName("signals the identity matrix of an sRGB description only in 4:4:4, where AV1 allows it")
    void identityMatrix(Av1EncoderUsage usage, ChromaFormat format) {
        var srgb = new ColorInfo(com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY,
                com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT709,
                com.github.auties00.smalt.image.TransferCharacteristics.SRGB);
        var base = usage == Av1EncoderUsage.ALL_INTRA ? Av1EncoderOptions.forIntraOnly(60).toBuilder()
                : options(usage, format, 8).toBuilder();
        var options = base.timeBase(TIME_BASE).pictureFormat(format, 8).color(srgb).build();
        var sources = new ArrayList<Picture>();
        for (var p : Vp9TestVideo.synthesize(WIDTH, HEIGHT, 3)) {
            sources.add(ImageOps.toYuv(p, format, srgb));
        }
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            var decoded = decode(encoder, encode(encoder, sources));
            assertEquals(sources.size(), decoded.size(), "decoded frames");
            for (var i = 0; i < decoded.size(); i++) {
                var yuv = (YuvPicture) decoded.get(i);
                assertEquals(format, yuv.chromaFormat(), "frame " + i + " format");
                assertEquals(WIDTH, yuv.width(), "frame " + i + " width");
                assertTrue(psnr(sources.get(i), yuv, 8) > 25, "frame " + i);
            }
        }
    }
}
