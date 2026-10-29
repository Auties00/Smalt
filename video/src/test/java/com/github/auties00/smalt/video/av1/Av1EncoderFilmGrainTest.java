package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.Av1Context;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.FilmGrainData;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.av1.internal.encoder.FilmGrains;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the film grain of the AV1 encoder: grain tables in every usage and picture format, whose
 * parameters every frame header must carry and whose synthesis dav1d must reproduce exactly, the
 * reuse of a reference frame's parameters, time ranges without grain, the denoising estimate of
 * the grain of a noisy source, against libaom's estimate when ffmpeg has libaom, and the
 * interoperability of the table text with libaom.
 */
class Av1EncoderFilmGrainTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 96;

    private static final int HEIGHT = 64;

    /** A grain of lag 2 with luma and chroma scaling functions, as an AV1 encoder may estimate it. */
    static Av1FilmGrain grain() {
        var n = 2 * 2 * 3;
        var luma = new int[n];
        var cb = new int[n + 1];
        var cr = new int[n + 1];
        for (var i = 0; i < n; i++) {
            luma[i] = (i * 7) % 11 - 5;
            cb[i] = (i * 5) % 7 - 3;
            cr[i] = (i * 3) % 5 - 2;
        }
        cb[n] = 20;
        cr[n] = -12;
        return Av1FilmGrain.builder().randomSeed(1234)
                .lumaPoints(List.of(new Av1FilmGrain.ScalingPoint(0, 20), new Av1FilmGrain.ScalingPoint(80, 60),
                        new Av1FilmGrain.ScalingPoint(160, 40), new Av1FilmGrain.ScalingPoint(255, 30)))
                .chromaPoints(List.of(new Av1FilmGrain.ScalingPoint(0, 30), new Av1FilmGrain.ScalingPoint(255, 20)),
                        List.of(new Av1FilmGrain.ScalingPoint(10, 25), new Av1FilmGrain.ScalingPoint(200, 35)))
                .scalingShift(10).arCoefficients(2, 7, luma, cb, cr).grainScaleShift(0).overlap(true).build();
    }

    record Decoded(byte[] samples, boolean grain, boolean update, FilmGrainData params) {
    }

    /** Decodes with the internal decoder, grain applied or not, keeping each shown frame's grain header. */
    static List<Decoded> decode(List<Packet> packets, boolean applyGrain) {
        var context = new Av1Context(MediaLimits.DEFAULT);
        context.filmGrain(applyGrain);
        var out = new ArrayList<Decoded>();
        var pictures = new ArrayList<Av1Picture>();
        for (var packet : packets) {
            pictures.clear();
            context.decode(packet.data(), 0, packet.data().length, packet.pts(), 0, pictures);
            for (var picture : pictures) {
                var hdr = picture.frameHdr;
                var params = new FilmGrainData();
                params.copyFrom(hdr.filmGrain);
                out.add(new Decoded(raw(picture), hdr.filmGrainPresent, hdr.filmGrainUpdate, params));
                picture.release();
            }
        }
        return out;
    }

    /** Returns the visible samples of a picture as ffmpeg writes them: bytes at 8 bits, 16-bit little-endian words above. */
    static byte[] raw(Av1Picture picture) {
        var out = new ByteArrayOutputStream();
        var planes = picture.data[1] == null ? 1 : 3;
        var ssx = picture.layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        var ssy = picture.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        for (var pl = 0; pl < planes; pl++) {
            var w = pl == 0 ? picture.w : (picture.w + ssx) >> ssx;
            var h = pl == 0 ? picture.h : (picture.h + ssy) >> ssy;
            var stride = picture.stride[pl == 0 ? 0 : 1];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var v = picture.data[pl][y * stride + x];
                    out.write(v & 0xFF);
                    if (picture.bpc > 8) {
                        out.write((v >> 8) & 0xFF);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    static Picture convert(YuvPicture picture, ChromaFormat format, int depth) {
        return depth == 8 ? ImageOps.toYuv(picture, format, ColorInfo.BT709_LIMITED)
                : ImageOps.toYuv16(picture, format, ColorInfo.BT709_LIMITED, depth);
    }

    static List<Packet> encode(Av1EncoderOptions options, List<Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static Av1EncoderOptions.Builder base(Av1EncoderUsage usage) {
        return switch (usage) {
            case REALTIME -> Av1EncoderOptions.forRealtime().toBuilder().bitrate(500_000).dropFrames(false);
            case GOOD_QUALITY -> Av1EncoderOptions.ofQuality(55).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(6);
            case ALL_INTRA -> Av1EncoderOptions.forIntraOnly(55).toBuilder().speed(Av1EncoderSpeed.FASTEST);
        };
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

    static void assertDav1d(List<Packet> packets, List<Decoded> withGrain, String name, String pixelFormat)
            throws IOException {
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var units = new ArrayList<byte[]>();
        for (var p : packets) {
            units.add(p.data());
        }
        var file = Scratch.directory("av1-grain").resolve(name + ".ivf");
        Files.write(file, Av1EncTesting.ivf(WIDTH, HEIGHT, units));
        var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), pixelFormat, 0);
        var ours = new ByteArrayOutputStream();
        for (var d : withGrain) {
            ours.writeBytes(d.samples());
        }
        assertArrayEquals(reference, ours.toByteArray(), name + ": dav1d output with grain");
    }

    static Stream<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var usage : Av1EncoderUsage.values()) {
            out.add(Arguments.of(usage, ChromaFormat.YUV420, 8));
            out.add(Arguments.of(usage, ChromaFormat.YUV420, 10));
            out.add(Arguments.of(usage, ChromaFormat.YUV444, 8));
            out.add(Arguments.of(usage, ChromaFormat.YUV422, 12));
            out.add(Arguments.of(usage, ChromaFormat.MONOCHROME, 10));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}-bit")
    @MethodSource("cases")
    @DisplayName("signals a table's grain in every frame and decodes with it like dav1d")
    void table(Av1EncoderUsage usage, ChromaFormat format, int depth) throws IOException {
        var grain = grain();
        var pictures = new ArrayList<Picture>();
        for (var p : Vp9TestVideo.synthesize(WIDTH, HEIGHT, 8)) {
            pictures.add(convert(p, format, depth));
        }
        var options = base(usage).timeBase(TIME_BASE).pictureFormat(format, depth)
                .filmGrain(Av1FilmGrainTable.constant(grain)).build();
        var packets = encode(options, pictures);
        var plain = decode(packets, false);
        var grainy = decode(packets, true);
        assertEquals(8, grainy.size(), "frames");
        var seq = new SequenceHeader();
        seq.monochrome = format == ChromaFormat.MONOCHROME;
        seq.ssHor = format == ChromaFormat.YUV444 ? 0 : 1;
        seq.ssVer = format == ChromaFormat.YUV420 || format == ChromaFormat.MONOCHROME ? 1 : 0;
        var expected = FilmGrains.toData(grain, seq);
        var seeds = new ArrayList<Integer>();
        for (var i = 0; i < grainy.size(); i++) {
            var d = grainy.get(i);
            assertTrue(d.grain(), "frame " + i + " signals grain");
            var params = d.params();
            seeds.add(params.seed);
            expected.seed = params.seed;
            assertTrue(sameParameters(expected, params), "frame " + i + " grain parameters");
            assertFalse(Arrays.equals(plain.get(i).samples(), d.samples()), "frame " + i + " has grain");
        }
        assertEquals(grain.randomSeed(), seeds.getFirst(), "first seed");
        if (usage != Av1EncoderUsage.GOOD_QUALITY) {
            for (var i = 1; i < seeds.size(); i++) {
                assertEquals(FilmGrains.nextSeed(seeds.get(i - 1)), seeds.get(i), "seed of frame " + i);
            }
        } else {
            assertEquals(seeds.size(), seeds.stream().distinct().count(), "distinct seeds");
        }
        if (usage == Av1EncoderUsage.REALTIME) {
            assertTrue(grainy.stream().skip(1).anyMatch(d -> !d.update()), "an inter frame reuses a reference's grain");
        }
        assertDav1d(packets, grainy, usage + "-" + format + "-" + depth, pixelFormat(format, depth));
    }

    private static boolean sameParameters(FilmGrainData a, FilmGrainData b) {
        if (a.numYPoints != b.numYPoints || a.chromaScalingFromLuma != b.chromaScalingFromLuma
                || a.scalingShift != b.scalingShift || a.arCoeffLag != b.arCoeffLag || a.arCoeffShift != b.arCoeffShift
                || a.grainScaleShift != b.grainScaleShift || a.overlapFlag != b.overlapFlag
                || a.clipToRestrictedRange != b.clipToRestrictedRange || !Arrays.equals(a.numUvPoints, b.numUvPoints)
                || !Arrays.equals(a.uvMult, b.uvMult) || !Arrays.equals(a.uvLumaMult, b.uvLumaMult)
                || !Arrays.equals(a.uvOffset, b.uvOffset)) {
            return false;
        }
        for (var i = 0; i < a.numYPoints; i++) {
            if (!Arrays.equals(a.yPoints[i], b.yPoints[i])) {
                return false;
            }
        }
        var n = 2 * a.arCoeffLag * (a.arCoeffLag + 1);
        if (a.numYPoints > 0 && !Arrays.equals(a.arCoeffsY, 0, n, b.arCoeffsY, 0, n)) {
            return false;
        }
        for (var pl = 0; pl < 2; pl++) {
            for (var i = 0; i < a.numUvPoints[pl]; i++) {
                if (!Arrays.equals(a.uvPoints[pl][i], b.uvPoints[pl][i])) {
                    return false;
                }
            }
            if (a.numUvPoints[pl] > 0 && !Arrays.equals(a.arCoeffsUv[pl], 0, n + 1, b.arCoeffsUv[pl], 0, n + 1)) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("signals no grain outside the table's ranges")
    void ranges() throws IOException {
        var grain = grain();
        // frames are 1/30 s apart: grain for the first four, none for the next two, grain again after
        var table = Av1FilmGrainTable.of(List.of(
                new Av1FilmGrainTable.Entry(Duration.ZERO, Duration.ofMillis(130), Optional.of(grain)),
                new Av1FilmGrainTable.Entry(Duration.ofMillis(130), Duration.ofMillis(190), Optional.empty()),
                new Av1FilmGrainTable.Entry(Duration.ofMillis(190), Duration.ofSeconds(10), Optional.of(grain))));
        var pictures = new ArrayList<Picture>(Vp9TestVideo.synthesize(WIDTH, HEIGHT, 8));
        var options = base(Av1EncoderUsage.REALTIME).timeBase(TIME_BASE).filmGrain(table).build();
        var packets = encode(options, pictures);
        var grainy = decode(packets, true);
        var signaled = new boolean[grainy.size()];
        for (var i = 0; i < grainy.size(); i++) {
            signaled[i] = grainy.get(i).grain();
        }
        assertArrayEquals(new boolean[]{true, true, true, true, false, false, true, true}, signaled);
        assertDav1d(packets, grainy, "ranges", "yuv420p");
    }

    @Test
    @DisplayName("rejects 4:2:0 grain with chroma points for one plane only")
    void rejectsUncodableGrain() {
        var grain = grain().toBuilder().chromaPoints(List.of(new Av1FilmGrain.ScalingPoint(0, 10)), List.of()).build();
        var options = base(Av1EncoderUsage.REALTIME).filmGrain(Av1FilmGrainTable.constant(grain)).build();
        assertThrows(IllegalArgumentException.class, () -> new Av1Encoder(WIDTH, HEIGHT, options));
        var mono = options.toBuilder().pictureFormat(ChromaFormat.MONOCHROME, 8).build();
        new Av1Encoder(WIDTH, HEIGHT, mono).close();
    }

    @Test
    @DisplayName("rejects a film grain table together with denoising")
    void rejectsTableAndDenoising() {
        var builder = Av1EncoderOptions.forRealtime().toBuilder().filmGrain(Av1FilmGrainTable.constant(grain()))
                .denoise(20);
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.forRealtime().toBuilder().denoise(0).build());
        assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.forRealtime().toBuilder().denoise(51).build());
        assertEquals(Optional.empty(), builder.noFilmGrain().build().filmGrain());
    }

    @Nested
    @DisplayName("denoising")
    class Denoising {
        /** A smooth moving picture with Gaussian noise of a standard deviation added, at 8 bits. */
        static List<Picture> noisy(double sigma, int frames, ChromaFormat format, int depth) {
            var random = new SplittableRandom(42);
            var out = new ArrayList<Picture>();
            var scale = 1 << (depth - 8);
            for (var f = 0; f < frames; f++) {
                var clean = Yuv16Picture.allocate(WIDTH * 2, HEIGHT * 2, format, depth, false, ColorInfo.BT709_LIMITED);
                var w = clean.width();
                var h = clean.height();
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var base = 60 + 0.5 * x + 0.3 * y + 2 * f + 30 * Math.sin((x + 2 * f) / 23.0);
                        var v = (base + sigma * random.nextGaussian()) * scale;
                        clean.y()[y * clean.yStride() + x] = (short) Math.clamp(Math.round(v), 0, (1 << depth) - 1);
                    }
                }
                if (format.hasChroma()) {
                    for (var y = 0; y < clean.chromaHeight(); y++) {
                        for (var x = 0; x < clean.chromaWidth(); x++) {
                            var u = (128 + 10 * Math.cos(x / 17.0) + sigma / 2 * random.nextGaussian()) * scale;
                            var v = (128 - 8 * Math.sin(y / 13.0) + sigma / 2 * random.nextGaussian()) * scale;
                            clean.u()[y * clean.uvStride() + x] = (short) Math.clamp(Math.round(u), 0, (1 << depth) - 1);
                            clean.v()[y * clean.uvStride() + x] = (short) Math.clamp(Math.round(v), 0, (1 << depth) - 1);
                        }
                    }
                }
                out.add(depth == 8 ? ImageOps.toEightBit(clean) : clean);
            }
            return out;
        }

        static double meanSquaredDifference(byte[] a, byte[] b, int depth) {
            double sum = 0;
            var count = 0;
            var step = depth > 8 ? 2 : 1;
            for (var i = 0; i + step - 1 < a.length; i += step) {
                var va = step == 1 ? a[i] & 0xFF : (a[i] & 0xFF) | (a[i + 1] & 0xFF) << 8;
                var vb = step == 1 ? b[i] & 0xFF : (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
                double d = va - vb;
                sum += d * d;
                count++;
            }
            return sum / count;
        }

        static Stream<Arguments> formats() {
            return Stream.of(Arguments.of(Av1EncoderUsage.REALTIME, ChromaFormat.YUV420, 8),
                    Arguments.of(Av1EncoderUsage.GOOD_QUALITY, ChromaFormat.YUV420, 10),
                    Arguments.of(Av1EncoderUsage.REALTIME, ChromaFormat.YUV422, 8),
                    Arguments.of(Av1EncoderUsage.ALL_INTRA, ChromaFormat.MONOCHROME, 8));
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("formats")
        @DisplayName("estimates grain of the source's strength and decodes with it like dav1d")
        void estimates(Av1EncoderUsage usage, ChromaFormat format, int depth) throws IOException {
            var sigma = 5.0;
            var pictures = noisy(sigma, 6, format, depth);
            var options = base(usage).timeBase(TIME_BASE).pictureFormat(format, depth).denoise(25).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new Av1Encoder(WIDTH * 2, HEIGHT * 2, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
            }
            var plain = decode(packets, false);
            var grainy = decode(packets, true);
            assertEquals(pictures.size(), grainy.size(), "frames");
            double synthesized = 0;
            for (var i = 0; i < grainy.size(); i++) {
                assertTrue(grainy.get(i).grain(), "frame " + i + " signals grain");
                assertTrue(grainy.get(i).params().numYPoints > 0, "frame " + i + " has a luma scaling function");
                if (format == ChromaFormat.MONOCHROME) {
                    assertEquals(0, grainy.get(i).params().numUvPoints[0], "monochrome has no chroma grain");
                }
                // only the luma plane: the first width * height samples
                var lumaBytes = WIDTH * 2 * HEIGHT * 2 * (depth > 8 ? 2 : 1);
                synthesized += meanSquaredDifference(Arrays.copyOf(plain.get(i).samples(), lumaBytes),
                        Arrays.copyOf(grainy.get(i).samples(), lumaBytes), depth);
            }
            // the synthesized grain's deviation at the 8-bit scale is that of the source noise, within a factor
            var deviation = Math.sqrt(synthesized / grainy.size()) / (1 << (depth - 8));
            assertTrue(deviation > sigma * 0.5 && deviation < sigma * 1.6, "synthesized grain deviation " + deviation);
            var units = new ArrayList<byte[]>();
            for (var p : packets) {
                units.add(p.data());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = Scratch.directory("av1-grain").resolve("denoise-" + usage + "-" + format + "-" + depth + ".ivf");
                Files.write(file, Av1EncTesting.ivf(WIDTH * 2, HEIGHT * 2, units));
                var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), pixelFormat(format, depth), 0);
                var ours = new ByteArrayOutputStream();
                for (var d : grainy) {
                    ours.writeBytes(d.samples());
                }
                assertArrayEquals(reference, ours.toByteArray(), "dav1d output with grain");
            }
        }

        /**
         * Returns the deviation, at the 8-bit scale, of the luma grain the frames of a stream that
         * signal grain synthesize, or 0 when none does.
         */
        static double synthesizedDeviation(List<Packet> packets) {
            var plain = decode(packets, false);
            var grainy = decode(packets, true);
            var lumaBytes = WIDTH * 2 * HEIGHT * 2;
            double sum = 0;
            var frames = 0;
            for (var i = 0; i < grainy.size(); i++) {
                if (grainy.get(i).grain()) {
                    sum += meanSquaredDifference(Arrays.copyOf(plain.get(i).samples(), lumaBytes),
                            Arrays.copyOf(grainy.get(i).samples(), lumaBytes), 8);
                    frames++;
                }
            }
            return frames == 0 ? 0 : Math.sqrt(sum / frames);
        }

        @Test
        @DisplayName("estimates grain of the strength libaom's denoising estimates from the same source")
        void libaomEstimates() throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pictures = noisy(5.0, 6, ChromaFormat.YUV420, 8);
            var raw = new ByteArrayOutputStream();
            for (var p : pictures) {
                var yuv = (YuvPicture) p;
                for (var y = 0; y < yuv.height(); y++) {
                    raw.write(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    raw.write(yuv.u(), y * yuv.uvStride(), yuv.chromaWidth());
                }
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    raw.write(yuv.v(), y * yuv.uvStride(), yuv.chromaWidth());
                }
            }
            var directory = Scratch.directory("av1-grain");
            var source = directory.resolve("noisy.yuv");
            Files.write(source, raw.toByteArray());
            var output = directory.resolve("libaom-denoise.ivf");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "rawvideo",
                    "-pix_fmt", "yuv420p", "-s", (WIDTH * 2) + "x" + (HEIGHT * 2), "-r", "30", "-i", source.toString(),
                    "-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "30", "-denoise-noise-level", "25",
                    output.toString()));
            var reference = new ArrayList<Packet>();
            for (var unit : Av1Streams.units(Files.readAllBytes(output))) {
                reference.add(new Packet(1, unit.data(), unit.pts(), unit.pts(), 1, false));
            }
            var options = base(Av1EncoderUsage.REALTIME).timeBase(TIME_BASE).denoise(25).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new Av1Encoder(WIDTH * 2, HEIGHT * 2, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
            }
            var theirs = synthesizedDeviation(reference);
            var ours = synthesizedDeviation(packets);
            assertTrue(ours > theirs * 0.6 && ours < theirs * 1.6,
                    "synthesized grain deviation " + ours + ", libaom's " + theirs);
        }

        @Test
        @DisplayName("denoises: the frames coded without grain are closer to the clean picture than the noisy source")
        void denoises() {
            var sigma = 6.0;
            var noisy = noisy(sigma, 4, ChromaFormat.YUV420, 8);
            var clean = noisy(0, 4, ChromaFormat.YUV420, 8);
            var options = base(Av1EncoderUsage.ALL_INTRA).quality(95).timeBase(TIME_BASE).denoise(30).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new Av1Encoder(WIDTH * 2, HEIGHT * 2, options)) {
                for (var i = 0; i < noisy.size(); i++) {
                    encoder.encode(new VideoFrame(noisy.get(i), i * 3000L, 3000, false), packets::add);
                }
            }
            var plain = decode(packets, false);
            var lumaBytes = WIDTH * 2 * HEIGHT * 2;
            for (var i = 0; i < plain.size(); i++) {
                var cleanY = Arrays.copyOf(((YuvPicture) clean.get(i)).y(), lumaBytes);
                var noisyY = Arrays.copyOf(((YuvPicture) noisy.get(i)).y(), lumaBytes);
                var coded = Arrays.copyOf(plain.get(i).samples(), lumaBytes);
                var noisyError = meanSquaredDifference(noisyY, cleanY, 8);
                var codedError = meanSquaredDifference(coded, cleanY, 8);
                assertTrue(codedError < noisyError * 0.7, "frame " + i + ": coded " + codedError + ", noisy " + noisyError);
            }
        }
    }

    @Test
    @DisplayName("the table text is libaom's: an aomenc table and its parameters round-trip, and bad text is rejected")
    void tableText() {
        var text = "filmgrn1\n"
                + "E 0 10000000 1 7391 1\n"
                + "\tp 3 7 0 11 0 1 128 192 256 128 192 256\n"
                + "\tsY 6  0 20 40 28 94 30 144 28 196 26 255 24\n"
                + "\tsCb 2 0 18 255 16\n"
                + "\tsCr 2 0 15 255 14\n"
                + "\tcY 1 -2 3 -4 5 -6 7 -8 9 -10 11 -12 13 -14 15 -16 17 -18 19 -20 21 -22 23 -24\n"
                + "\tcCb 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 33\n"
                + "\tcCr 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 -7\n"
                + "E 10000000 20000000 0 0 0\n"
                + "E 20000000 30000000 1 10 0\n";
        var table = Av1FilmGrainTable.parse(text);
        assertEquals(3, table.entries().size());
        var first = table.entries().getFirst().grain().orElseThrow();
        assertEquals(7391, first.randomSeed());
        assertEquals(3, first.arCoefficientLag());
        assertEquals(7, first.arCoefficientShift());
        assertEquals(11, first.scalingShift());
        assertEquals(6, first.lumaPoints().size());
        assertEquals(new Av1FilmGrain.ScalingPoint(94, 30), first.lumaPoints().get(2));
        assertEquals(33, first.cbArCoefficients()[24]);
        assertEquals(-24, first.lumaArCoefficients()[23]);
        assertTrue(table.entries().get(1).grain().isEmpty(), "an entry adding no grain");
        // an entry without parameters repeats the previous ones with its own seed
        assertEquals(first.toBuilder().randomSeed(10).build(), table.entries().get(2).grain().orElseThrow());
        assertEquals(Optional.empty(), table.lookup(Duration.ofMillis(1500)));
        assertEquals(Optional.of(first), table.lookup(Duration.ofMillis(999)));
        assertEquals(table, Av1FilmGrainTable.parse(table.format()));
        var open = Av1FilmGrainTable.parse("filmgrn1\nE 0 9223372036854775807 0 0 0\n");
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), open.entries().getFirst().end());
        assertTrue(open.format().contains("E 0 9223372036854775807 0 0 0"), open.format());
        assertEquals(text.substring(0, text.indexOf("E 10000000")),
                table.format().substring(0, table.format().indexOf("E 10000000")));
        for (var bad : List.of("", "filmgrn2\n", "filmgrn1\nE 0 10 1 0 0\n", "filmgrn1\nE 0 10 1 0 1\n\tp 4",
                "filmgrn1\nE 10 5 0 0 0\n", "filmgrn1\nE 0 10 1 70000 1\n\tp 0 6 0 8 0 1 128 192 256 128 192 256\n"
                        + "\tsY 0\n\tsCb 0\n\tsCr 0\n\tcY\n\tcCb 0\n\tcCr 0\n", "filmgrn1\nE x 10 0 0 0\n")) {
            assertThrows(IllegalArgumentException.class, () -> Av1FilmGrainTable.parse(bad), bad);
        }
    }

    @Test
    @DisplayName("libaom reads the table text: its frames carry the table's grain")
    void libaomReadsTable() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var grain = grain();
        var table = Av1FilmGrainTable.constant(grain);
        var directory = Scratch.directory("av1-grain");
        var tableFile = directory.resolve("table.tbl");
        Files.writeString(tableFile, table.format());
        var source = directory.resolve("source.y4m");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=size=96x64:rate=30", "-frames:v", "4", "-pix_fmt", "yuv420p", source.toString()));
        var output = directory.resolve("libaom.ivf");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i", source.toString(),
                "-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "40", "-aom-params",
                "film-grain-table=" + tableFile.toString().replace('\\', '/'), output.toString()));
        var packets = new ArrayList<Packet>();
        for (var unit : Av1Streams.units(Files.readAllBytes(output))) {
            packets.add(new Packet(1, unit.data(), unit.pts(), unit.pts(), 1, false));
        }
        var decoded = decode(packets, true);
        assertFalse(decoded.isEmpty(), "frames");
        var seq = new SequenceHeader();
        seq.ssHor = 1;
        seq.ssVer = 1;
        var expected = FilmGrains.toData(grain, seq);
        for (var i = 0; i < decoded.size(); i++) {
            assertTrue(decoded.get(i).grain(), "libaom frame " + i + " signals grain");
            expected.seed = decoded.get(i).params().seed;
            assertTrue(sameParameters(expected, decoded.get(i).params()), "libaom frame " + i + " grain parameters");
        }
        assertNotEquals(0, decoded.getFirst().params().seed);
    }
}
