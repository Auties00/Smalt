package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.UncompressedVideoDecoder;
import com.github.auties00.smalt.video.UncompressedVideoEncoder;
import com.github.auties00.smalt.video.UncompressedVideoFormat;
import com.github.auties00.smalt.video.UncompressedVideoFormat.Compression;
import com.github.auties00.smalt.video.UncompressedVideoFormat.Interleave;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ISO/IEC 23001-17 uncompressed video in MP4 ({@code uncv}): Smalt's encoder, muxer, demuxer and decoder round trip
 * every layout the encoder writes bit-exact; the frames and boxes match ffmpeg's repacking of the same samples and
 * the files of {@code tools/testdata/behavior/make-uncv-samples.py}, written without Smalt; ffprobe and MP4Box read
 * Smalt's files; hand-built layouts (blocks, pixel padding, profiles) decode through the track path; and malformed
 * input fails cleanly.
 */
@DisplayName("ISO/IEC 23001-17 uncompressed video in MP4")
class Mp4UncompressedVideoTest {
    private static final Rational RATE = new Rational(25, 1);

    private static final ColorInfo RGB = ColorInfo.BT601_LIMITED.withMatrix(MatrixCoefficients.IDENTITY)
            .withRange(ColorRange.FULL);

    record Case(String name, UncompressedVideoFormat format, int width, int height) {
        @Override
        public String toString() {
            return name + " " + width + "x" + height;
        }
    }

    static List<Case> layouts() {
        var cases = new ArrayList<Case>();
        var y420 = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8);
        var y422 = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 8);
        var y444 = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 8);
        cases.add(new Case("4:2:0 component", y420, 30, 14));
        cases.add(new Case("4:2:0 component odd", y420, 31, 15));
        cases.add(new Case("4:2:0 mixed", y420.withInterleave(Interleave.MIXED), 30, 14));
        cases.add(new Case("4:2:0 mixed odd rows of 16", y420.withInterleave(Interleave.MIXED).withRowAlignment(16),
                31, 15));
        cases.add(new Case("4:2:0 tile-component 2x2", y420.withInterleave(Interleave.TILE_COMPONENT).withTiles(2, 2),
                32, 16));
        cases.add(new Case("4:2:0 component tiles 3x1", y420.withTiles(3, 1), 36, 10));
        cases.add(new Case("4:2:0 10-bit aligned little-endian",
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 10).withAlignedComponents(true)
                        .withLittleEndian(true), 30, 14));
        cases.add(new Case("4:2:0 12-bit packed", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 12), 30, 14));
        cases.add(new Case("4:2:0 5-bit packed", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 5), 30, 14));
        cases.add(new Case("4:2:0 deflate", y420.withCompression(Compression.DEFLATE), 30, 14));
        cases.add(new Case("4:2:2 multi-Y", y422.withInterleave(Interleave.MULTI_Y), 30, 14));
        cases.add(new Case("4:2:2 multi-Y odd", y422.withInterleave(Interleave.MULTI_Y), 31, 7));
        cases.add(new Case("4:2:2 multi-Y 10-bit aligned little-endian rows of 16 zlib",
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 10).withInterleave(Interleave.MULTI_Y)
                        .withAlignedComponents(true).withLittleEndian(true).withRowAlignment(16)
                        .withCompression(Compression.ZLIB), 30, 14));
        cases.add(new Case("4:2:2 row", y422.withInterleave(Interleave.ROW), 30, 14));
        cases.add(new Case("4:2:2 mixed", y422.withInterleave(Interleave.MIXED), 30, 14));
        cases.add(new Case("4:2:2 16-bit little-endian",
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 16).withLittleEndian(true), 30, 14));
        cases.add(new Case("4:4:4 pixel alpha", y444.withInterleave(Interleave.PIXEL).withAlpha(true), 30, 14));
        cases.add(new Case("4:4:4 row alpha", y444.withInterleave(Interleave.ROW).withAlpha(true), 30, 14));
        cases.add(new Case("4:4:4 9-bit pixel packed",
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 9).withInterleave(Interleave.PIXEL), 31, 15));
        cases.add(new Case("4:4:4 16-bit tile-component 3x1",
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 16).withInterleave(Interleave.TILE_COMPONENT)
                        .withTiles(3, 1), 30, 14));
        cases.add(new Case("4:2:0 mixed alpha", y420.withInterleave(Interleave.MIXED).withAlpha(true), 30, 14));
        cases.add(new Case("RGB pixel", UncompressedVideoFormat.ofRgb(8), 30, 14));
        cases.add(new Case("RGBA component", UncompressedVideoFormat.ofRgb(8).withAlpha(true)
                .withInterleave(Interleave.COMPONENT), 30, 14));
        cases.add(new Case("RGBA zlib", UncompressedVideoFormat.ofRgb(8).withAlpha(true)
                .withCompression(Compression.ZLIB), 30, 14));
        cases.add(new Case("RGB 10-bit aligned", UncompressedVideoFormat.ofRgb(10).withAlignedComponents(true), 30, 14));
        cases.add(new Case("RGBA 16-bit row little-endian tiles 2x2", UncompressedVideoFormat.ofRgb(16).withAlpha(true)
                .withInterleave(Interleave.ROW).withLittleEndian(true).withTiles(2, 2), 30, 14));
        cases.add(new Case("RGB 4-bit pixel", UncompressedVideoFormat.ofRgb(4), 31, 15));
        cases.add(new Case("RGB 1-bit", UncompressedVideoFormat.ofRgb(1).withInterleave(Interleave.COMPONENT), 31, 15));
        cases.add(new Case("monochrome", UncompressedVideoFormat.ofMonochrome(8), 30, 14));
        cases.add(new Case("monochrome 1-bit rows of 4", UncompressedVideoFormat.ofMonochrome(1).withRowAlignment(4),
                31, 15));
        cases.add(new Case("monochrome 12-bit row", UncompressedVideoFormat.ofMonochrome(12)
                .withInterleave(Interleave.ROW), 30, 14));
        cases.add(new Case("monochrome alpha 16-bit pixel little-endian", UncompressedVideoFormat.ofMonochrome(16)
                .withAlpha(true).withInterleave(Interleave.PIXEL).withLittleEndian(true), 30, 14));
        return cases;
    }

    /** {@return a picture of random samples in the representation the decoder gives frames of a format} */
    static Picture source(UncompressedVideoFormat format, int width, int height, long seed) {
        var random = new Random(seed);
        var depth = format.bitDepth();
        var color = format.colorModel() == UncompressedVideoFormat.ColorModel.RGB ? RGB : ColorInfo.BT601_LIMITED;
        var max = (1 << depth) - 1;
        if (depth > 8) {
            var picture = Yuv16Picture.allocate(width, height, format.chromaFormat(), depth, format.alpha(), color);
            fill(random, picture.y(), max);
            if (picture.u() != null) {
                fill(random, picture.u(), max);
                fill(random, picture.v(), max);
            }
            if (picture.alpha() != null) {
                fill(random, picture.alpha(), max);
            }
            return picture;
        }
        var picture = YuvPicture.allocate(width, height, format.chromaFormat(), format.alpha(), color);
        fill(random, picture.y(), max);
        if (picture.u() != null) {
            fill(random, picture.u(), max);
            fill(random, picture.v(), max);
        }
        if (picture.alpha() != null) {
            fill(random, picture.alpha(), max);
        }
        return picture;
    }

    /** Fills a plane with values of a depth widened to 8 bits as the decoder widens them. */
    static void fill(Random random, byte[] plane, int max) {
        for (var i = 0; i < plane.length; i++) {
            var v = random.nextInt(max + 1);
            plane[i] = (byte) (max == 255 ? v : (v * 255 + max / 2) / max);
        }
    }

    static void fill(Random random, short[] plane, int max) {
        for (var i = 0; i < plane.length; i++) {
            plane[i] = (short) random.nextInt(max + 1);
        }
    }

    static void assertSamePicture(Picture expected, Picture actual, String message) {
        assertEquals(expected.getClass(), actual.getClass(), message + ": picture type");
        assertEquals(expected.width(), actual.width(), message);
        assertEquals(expected.height(), actual.height(), message);
        switch (expected) {
            case YuvPicture e -> {
                var a = (YuvPicture) actual;
                assertEquals(e.chromaFormat(), a.chromaFormat(), message);
                assertEquals(e.color().matrix(), a.color().matrix(), message + ": matrix");
                assertPlane(e.y(), e.yStride(), a.y(), a.yStride(), e.width(), e.height(), message + " Y");
                if (e.u() != null) {
                    assertPlane(e.u(), e.uvStride(), a.u(), a.uvStride(), e.chromaWidth(), e.chromaHeight(), message + " U");
                    assertPlane(e.v(), e.uvStride(), a.v(), a.uvStride(), e.chromaWidth(), e.chromaHeight(), message + " V");
                }
                assertEquals(e.alpha() != null, a.alpha() != null, message + ": alpha");
                if (e.alpha() != null) {
                    assertPlane(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), e.width(), e.height(),
                            message + " A");
                }
            }
            case Yuv16Picture e -> {
                var a = (Yuv16Picture) actual;
                assertEquals(e.chromaFormat(), a.chromaFormat(), message);
                assertEquals(e.bitDepth(), a.bitDepth(), message);
                assertEquals(e.color().matrix(), a.color().matrix(), message + ": matrix");
                assertPlane(e.y(), e.yStride(), a.y(), a.yStride(), e.width(), e.height(), message + " Y");
                if (e.u() != null) {
                    assertPlane(e.u(), e.uvStride(), a.u(), a.uvStride(), e.chromaWidth(), e.chromaHeight(), message + " U");
                    assertPlane(e.v(), e.uvStride(), a.v(), a.uvStride(), e.chromaWidth(), e.chromaHeight(), message + " V");
                }
                assertEquals(e.alpha() != null, a.alpha() != null, message + ": alpha");
                if (e.alpha() != null) {
                    assertPlane(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), e.width(), e.height(),
                            message + " A");
                }
            }
            default -> fail("unexpected picture " + expected);
        }
    }

    static void assertPlane(byte[] expected, int expectedStride, byte[] actual, int actualStride, int width, int height,
                            String message) {
        for (var y = 0; y < height; y++) {
            assertArrayEquals(Arrays.copyOfRange(expected, y * expectedStride, y * expectedStride + width),
                    Arrays.copyOfRange(actual, y * actualStride, y * actualStride + width), message + " row " + y);
        }
    }

    static void assertPlane(short[] expected, int expectedStride, short[] actual, int actualStride, int width, int height,
                            String message) {
        for (var y = 0; y < height; y++) {
            assertArrayEquals(Arrays.copyOfRange(expected, y * expectedStride, y * expectedStride + width),
                    Arrays.copyOfRange(actual, y * actualStride, y * actualStride + width), message + " row " + y);
        }
    }

    /** An encoded stream: its track and packets. */
    record Stream(VideoTrack track, List<Packet> packets, List<Picture> pictures) {
    }

    static Stream encode(UncompressedVideoFormat format, int width, int height, int frames) {
        var pictures = new ArrayList<Picture>();
        var packets = new ArrayList<Packet>();
        try (var encoder = new UncompressedVideoEncoder(format, width, height, RATE, null)) {
            for (var frame = 0; frame < frames; frame++) {
                var picture = source(format, width, height, 31L * frame + width);
                pictures.add(picture);
                encoder.encode(new VideoFrame(picture, frame, 1, true), packet -> packets.add(packet.withTrackId(1)));
            }
            var track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase()).width(width)
                    .height(height).frameRate(RATE).codecConfig(encoder.codecConfig().orElseThrow()).build();
            return new Stream(track, packets, pictures);
        }
    }

    static List<Picture> decode(VideoTrack track, List<Packet> packets) {
        var out = new ArrayList<Picture>();
        try (var decoder = VideoDecoders.create(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> out.add(copy(frame.picture())));
            }
        }
        return out;
    }

    /** {@return a copy of a picture the decoder reuses} */
    static Picture copy(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> {
                var out = YuvPicture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.alpha() != null,
                        yuv.color());
                System.arraycopy(yuv.y(), 0, out.y(), 0, yuv.y().length);
                if (yuv.u() != null) {
                    System.arraycopy(yuv.u(), 0, out.u(), 0, yuv.u().length);
                    System.arraycopy(yuv.v(), 0, out.v(), 0, yuv.v().length);
                }
                if (yuv.alpha() != null) {
                    System.arraycopy(yuv.alpha(), 0, out.alpha(), 0, yuv.alpha().length);
                }
                yield out;
            }
            case Yuv16Picture yuv -> {
                var out = Yuv16Picture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.bitDepth(),
                        yuv.alpha() != null, yuv.color());
                System.arraycopy(yuv.y(), 0, out.y(), 0, yuv.y().length);
                if (yuv.u() != null) {
                    System.arraycopy(yuv.u(), 0, out.u(), 0, yuv.u().length);
                    System.arraycopy(yuv.v(), 0, out.v(), 0, yuv.v().length);
                }
                if (yuv.alpha() != null) {
                    System.arraycopy(yuv.alpha(), 0, out.alpha(), 0, yuv.alpha().length);
                }
                yield out;
            }
            default -> throw new AssertionError(picture);
        };
    }

    static byte[] mux(Stream stream) {
        return MuxSupport.toMemory(List.of(stream.track()), stream.packets(),
                sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4()));
    }

    static VideoTrack videoTrack(ContainerProbe.Demuxed demuxed) {
        assertEquals(1, demuxed.tracks().size());
        return assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
    }

    /** {@return a sample of {@code samples/mp4}} */
    static byte[] resource(String name) {
        try (var in = Mp4UncompressedVideoTest.class.getResourceAsStream("/samples/mp4/" + name)) {
            if (in == null) {
                throw new AssertionError("missing sample " + name);
            }
            return in.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static byte[] inflate(byte[] data, boolean raw) {
        try (var in = new InflaterInputStream(new java.io.ByteArrayInputStream(data), new java.util.zip.Inflater(raw))) {
            return in.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("round trips through Smalt")
    class RoundTrips {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.mp4.Mp4UncompressedVideoTest#layouts")
        @DisplayName("encodes, muxes, demuxes and decodes every layout bit-exact")
        void mp4(Case layout) {
            var stream = encode(layout.format(), layout.width(), layout.height(), 3);
            for (var packet : stream.packets()) {
                assertTrue(packet.keyframe());
                if (layout.format().compression() == Compression.NONE) {
                    assertEquals(layout.format().frameSize(layout.width(), layout.height()), packet.data().length);
                } else {
                    var raw = inflate(packet.data(), layout.format().compression() == Compression.DEFLATE);
                    assertEquals(layout.format().frameSize(layout.width(), layout.height()), raw.length);
                }
            }
            var demuxed = MuxSupport.demux(mux(stream));
            var track = videoTrack(demuxed);
            assertEquals(Codec.UNCOMPRESSED_23001_17, track.codec());
            assertEquals(layout.width(), track.width());
            assertEquals(layout.height(), track.height());
            assertArrayEquals(layout.format().toCodecConfig(), track.codecConfig().orElseThrow());
            var packets = demuxed.packetsOf(track);
            assertEquals(3, packets.size());
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(stream.packets().get(i).data(), packets.get(i).data(), "packet " + i);
            }
            var decoded = decode(track, packets);
            assertEquals(3, decoded.size());
            for (var i = 0; i < 3; i++) {
                assertSamePicture(stream.pictures().get(i), decoded.get(i), layout + " frame " + i);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"4:2:0 component", "4:2:2 multi-Y 10-bit aligned little-endian rows of 16 zlib",
                "RGBA 16-bit row little-endian tiles 2x2"})
        @DisplayName("round trips through fragmented MP4 and QuickTime")
        void fragmentedAndQuickTime(String name) {
            var layout = layouts().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
            var stream = encode(layout.format(), layout.width(), layout.height(), 3);
            var fragmented = MuxSupport.toMemory(List.of(stream.track()), stream.packets(), FragmentedMp4Muxer::new);
            var quickTime = MuxSupport.toMemory(List.of(stream.track()), stream.packets(),
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime()));
            for (var file : List.of(fragmented, quickTime)) {
                var demuxed = MuxSupport.demux(file);
                var track = videoTrack(demuxed);
                assertArrayEquals(layout.format().toCodecConfig(), track.codecConfig().orElseThrow());
                var decoded = decode(track, demuxed.packetsOf(track));
                for (var i = 0; i < 3; i++) {
                    assertSamePicture(stream.pictures().get(i), decoded.get(i), name + " frame " + i);
                }
            }
        }

        @Test
        @DisplayName("keeps the color description of the track")
        void color() {
            var stream = encode(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8), 16, 8, 1);
            var color = ColorInfo.BT709_LIMITED;
            var track = stream.track().toBuilder().color(color).build();
            var demuxed = MuxSupport.demux(MuxSupport.toMemory(List.of(track), stream.packets(),
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4())));
            var read = videoTrack(demuxed);
            assertEquals(color.matrix(), read.color().orElseThrow().matrix());
            var picture = decode(read, demuxed.packetsOf(read)).getFirst();
            assertEquals(MatrixCoefficients.BT709, ((YuvPicture) picture).color().matrix());
        }

        @Test
        @DisplayName("converts pictures of another model or chroma format before coding them")
        void converts() {
            var source = (YuvPicture) source(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 8), 16, 8, 5);
            var format = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8);
            var expected = com.github.auties00.smalt.image.ops.ImageOps.toYuv(source, ChromaFormat.YUV420,
                    ColorInfo.BT601_LIMITED);
            var packets = new ArrayList<Packet>();
            try (var encoder = new UncompressedVideoEncoder(format, 16, 8, RATE, null)) {
                encoder.encode(new VideoFrame(source, 0, 1, true), packets::add);
                var track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase()).width(16)
                        .height(8).codecConfig(encoder.codecConfig().orElseThrow()).build();
                assertSamePicture(expected, decode(track, packets).getFirst(), "4:4:4 to 4:2:0");
            }
            var rgb = UncompressedVideoFormat.ofRgb(8);
            var asRgb = com.github.auties00.smalt.image.ops.ImageOps.toYuv(source, ChromaFormat.YUV444, RGB);
            packets.clear();
            try (var encoder = new UncompressedVideoEncoder(rgb, 16, 8, RATE, null)) {
                encoder.encode(new VideoFrame(source, 0, 1, true), packets::add);
                var track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase()).width(16)
                        .height(8).codecConfig(encoder.codecConfig().orElseThrow()).build();
                assertSamePicture(asRgb, decode(track, packets).getFirst(), "Y'CbCr to RGB");
            }
        }

        @Test
        @DisplayName("is created by VideoEncoders and VideoDecoders at the options' depth")
        void registries() {
            assertTrue(VideoEncoders.isSupported(Codec.UNCOMPRESSED_23001_17));
            assertTrue(VideoDecoders.isSupported(Codec.UNCOMPRESSED_23001_17));
            var options = VideoEncoderOptions.builder().size(16, 8).frameRate(RATE).bitrate(1_000_000).bitDepth(10).build();
            try (var encoder = VideoEncoders.create(Codec.UNCOMPRESSED_23001_17, options)) {
                assertEquals(Codec.UNCOMPRESSED_23001_17, encoder.codec());
                assertArrayEquals(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 10).toCodecConfig(),
                        encoder.codecConfig().orElseThrow());
            }
        }
    }

    @Nested
    @DisplayName("ffmpeg's packing of the same samples")
    class Ffmpeg {
        private static final int WIDTH = 32;
        private static final int HEIGHT = 16;

        record Packing(String name, UncompressedVideoFormat format, String planar, String packed) {
            @Override
            public String toString() {
                return name;
            }
        }

        static List<Packing> packings() {
            return List.of(
                    new Packing("yuv420p", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8), "yuv420p", "yuv420p"),
                    new Packing("nv12", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8)
                            .withInterleave(Interleave.MIXED), "yuv420p", "nv12"),
                    new Packing("nv16", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 8)
                            .withInterleave(Interleave.MIXED), "yuv422p", "nv16"),
                    new Packing("yuyv422", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 8)
                            .withInterleave(Interleave.MULTI_Y), "yuv422p", "yuyv422"),
                    new Packing("yuv420p10le", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 10)
                            .withAlignedComponents(true).withLittleEndian(true), "yuv420p10le", "yuv420p10le"),
                    new Packing("yuv444p16be", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 16), "yuv444p16le",
                            "yuv444p16be"),
                    new Packing("yuva444p", UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV444, 8).withAlpha(true),
                            "yuva444p", "yuva444p"),
                    new Packing("rgb24", UncompressedVideoFormat.ofRgb(8), "gbrp", "rgb24"),
                    new Packing("rgba", UncompressedVideoFormat.ofRgb(8).withAlpha(true), "gbrap", "rgba"),
                    new Packing("rgb48le", UncompressedVideoFormat.ofRgb(16).withLittleEndian(true), "gbrp16le", "rgb48le"),
                    new Packing("gbrp10le", UncompressedVideoFormat.ofRgb(10).withAlignedComponents(true)
                            .withLittleEndian(true).withInterleave(Interleave.COMPONENT), "gbrp10le", null),
                    new Packing("gray", UncompressedVideoFormat.ofMonochrome(8), "gray", "gray"),
                    new Packing("gray16le", UncompressedVideoFormat.ofMonochrome(16).withLittleEndian(true), "gray16le",
                            "gray16le"),
                    new Packing("monob", UncompressedVideoFormat.ofMonochrome(1), "gray", "monob"));
        }

        static byte[] source(String pixelFormat) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=1", "-frames:v", "1", "-pix_fmt", pixelFormat,
                    "-f", "rawvideo", "-"));
        }

        static byte[] convert(byte[] raw, String from, String to) {
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), raw, List.of("-v", "error", "-f", "rawvideo",
                    "-pix_fmt", from, "-s", WIDTH + "x" + HEIGHT, "-i", "pipe:0", "-sws_flags", "neighbor+bitexact",
                    "-f", "rawvideo", "-pix_fmt", to, "-"));
        }

        /** {@return a picture of ffmpeg's planar samples, in the format's chroma format and depth} */
        static Picture picture(UncompressedVideoFormat format, String planar, byte[] raw) {
            var rgb = format.colorModel() == UncompressedVideoFormat.ColorModel.RGB;
            var color = rgb ? RGB : ColorInfo.BT601_LIMITED;
            var chroma = format.chromaFormat();
            var planes = format.colorModel() == UncompressedVideoFormat.ColorModel.MONOCHROME ? 1 : 3;
            var count = planes + (format.alpha() ? 1 : 0);
            var chromaWidth = chroma.chromaWidth(WIDTH);
            var chromaHeight = chroma.chromaHeight(HEIGHT);
            var deep = planar.contains("16") || planar.contains("10");
            var bytes = deep ? 2 : 1;
            var offsets = new int[count];
            for (int p = 0, at = 0; p < count; p++) {
                offsets[p] = at;
                at += (p == 1 || p == 2 ? chromaWidth * chromaHeight : WIDTH * HEIGHT) * bytes;
            }
            if (deep) {
                var depth = format.bitDepth();
                var picture = Yuv16Picture.allocate(WIDTH, HEIGHT, chroma, depth, format.alpha(), color);
                var targets = new short[][]{picture.y(), picture.u(), picture.v(), picture.alpha()};
                var strides = new int[]{picture.yStride(), picture.uvStride(), picture.uvStride(), picture.alphaStride()};
                for (var p = 0; p < count; p++) {
                    var target = planes == 1 && p == 1 ? 3 : p;
                    var w = target == 1 || target == 2 ? chromaWidth : WIDTH;
                    var h = target == 1 || target == 2 ? chromaHeight : HEIGHT;
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            var at = offsets[p] + 2 * (y * w + x);
                            targets[target][y * strides[target] + x] = (short) ((raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8);
                        }
                    }
                }
                return picture;
            }
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, chroma, format.alpha(), color);
            var targets = new byte[][]{picture.y(), picture.u(), picture.v(), picture.alpha()};
            var strides = new int[]{picture.yStride(), picture.uvStride(), picture.uvStride(), picture.alphaStride()};
            for (var p = 0; p < count; p++) {
                var target = planes == 1 && p == 1 ? 3 : p;
                var w = target == 1 || target == 2 ? chromaWidth : WIDTH;
                var h = target == 1 || target == 2 ? chromaHeight : HEIGHT;
                for (var y = 0; y < h; y++) {
                    System.arraycopy(raw, offsets[p] + y * w, targets[target], y * strides[target], w);
                }
            }
            return picture;
        }

        /** {@return planes of green, blue and red reordered to red, green and blue} */
        static byte[] redFirst(byte[] planar) {
            var plane = planar.length / 3;
            var out = new byte[planar.length];
            System.arraycopy(planar, 2 * plane, out, 0, plane);
            System.arraycopy(planar, 0, out, plane, 2 * plane);
            return out;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("packings")
        @DisplayName("frames equal ffmpeg's packing of the planar samples and decode back to them")
        void packing(Packing packing) {
            var planar = source(packing.planar());
            Picture picture;
            byte[] expected;
            if (packing.format().bitDepth() == 1) {
                // a black and white source, so that every 1-bit sample is exact
                var gray = planar.clone();
                for (var i = 0; i < gray.length; i++) {
                    gray[i] = (byte) ((gray[i] & 0xFF) >= 128 ? 255 : 0);
                }
                planar = gray;
                var mono = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.MONOCHROME, false, ColorInfo.BT601_LIMITED);
                for (var y = 0; y < HEIGHT; y++) {
                    System.arraycopy(gray, y * WIDTH, mono.y(), y * mono.yStride(), WIDTH);
                }
                picture = mono;
                expected = convert(gray, "gray", "monob");
            } else {
                picture = picture(packing.format(), packing.planar(), planar);
                // ffmpeg's planar RGB stores green, blue and red; the format stores red, green and blue
                expected = packing.packed() != null ? convert(planar, packing.planar(), packing.packed())
                        : redFirst(planar);
            }
            var packets = new ArrayList<Packet>();
            try (var encoder = new UncompressedVideoEncoder(packing.format(), WIDTH, HEIGHT, RATE, null)) {
                encoder.encode(new VideoFrame(picture, 0, 1, true), packet -> packets.add(packet.withTrackId(1)));
                assertArrayEquals(expected, packets.getFirst().data(), packing + " frame bytes");
                var track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase()).width(WIDTH)
                        .height(HEIGHT).codecConfig(encoder.codecConfig().orElseThrow()).build();
                var ffmpegFrame = new Packet(1, expected, 0, 0, 1, true);
                assertSamePicture(picture, decode(track, List.of(ffmpegFrame)).getFirst(), packing.toString());
            }
        }
    }

    @Nested
    @DisplayName("files written without Smalt")
    class Samples {
        static int value(int c, int x, int y, int f, int depth) {
            return (37 * x + 101 * y + 53 * f + 71 * c) % (1 << depth);
        }

        static ContainerProbe.Demuxed sample(String name) {
            return MuxSupport.demux(resource(name));
        }

        @Test
        @DisplayName("uncv-yuv420.mp4: 8-bit 4:2:0 component frames")
        void yuv420() {
            var demuxed = sample("uncv-yuv420.mp4");
            var track = videoTrack(demuxed);
            var format = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8);
            assertArrayEquals(format.toCodecConfig(), track.codecConfig().orElseThrow());
            var decoded = decode(track, demuxed.packetsOf(track));
            assertEquals(4, decoded.size());
            for (var f = 0; f < 4; f++) {
                var picture = (YuvPicture) decoded.get(f);
                assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
                for (var y = 0; y < 16; y++) {
                    for (var x = 0; x < 32; x++) {
                        assertEquals(value(0, x, y, f, 8), picture.y()[y * picture.yStride() + x] & 0xFF);
                        if (x < 16 && y < 8) {
                            assertEquals(value(1, x, y, f, 8), picture.u()[y * picture.uvStride() + x] & 0xFF);
                            assertEquals(value(2, x, y, f, 8), picture.v()[y * picture.uvStride() + x] & 0xFF);
                        }
                    }
                }
                reencode(format, picture, demuxed.packetsOf(track).get(f).data(), Compression.NONE);
            }
        }

        @Test
        @DisplayName("uncv-rgba-zlib.mp4: zlib-compressed 8-bit RGBA pixel frames")
        void rgba() {
            var demuxed = sample("uncv-rgba-zlib.mp4");
            var track = videoTrack(demuxed);
            var format = UncompressedVideoFormat.ofRgb(8).withAlpha(true).withCompression(Compression.ZLIB);
            assertArrayEquals(format.toCodecConfig(), track.codecConfig().orElseThrow());
            var decoded = decode(track, demuxed.packetsOf(track));
            assertEquals(3, decoded.size());
            for (var f = 0; f < 3; f++) {
                var picture = (YuvPicture) decoded.get(f);
                assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 24; x++) {
                        assertEquals(value(0, x, y, f, 8), picture.v()[y * picture.uvStride() + x] & 0xFF, "R");
                        assertEquals(value(1, x, y, f, 8), picture.y()[y * picture.yStride() + x] & 0xFF, "G");
                        assertEquals(value(2, x, y, f, 8), picture.u()[y * picture.uvStride() + x] & 0xFF, "B");
                        assertEquals(value(3, x, y, f, 8), picture.alpha()[y * picture.alphaStride() + x] & 0xFF, "A");
                    }
                }
                reencode(format, picture, demuxed.packetsOf(track).get(f).data(), Compression.ZLIB);
            }
        }

        @Test
        @DisplayName("uncv-yuv422-10.mp4: 10-bit 4:2:2 multi-Y frames in padded rows, with a colr box")
        void yuv422() {
            var demuxed = sample("uncv-yuv422-10.mp4");
            var track = videoTrack(demuxed);
            assertEquals(MatrixCoefficients.BT709, track.color().orElseThrow().matrix());
            var format = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 10).withInterleave(Interleave.MULTI_Y)
                    .withAlignedComponents(true).withLittleEndian(true).withRowAlignment(16);
            assertArrayEquals(format.toCodecConfig(), track.codecConfig().orElseThrow());
            var decoded = decode(track, demuxed.packetsOf(track));
            assertEquals(3, decoded.size());
            for (var f = 0; f < 3; f++) {
                var picture = (Yuv16Picture) decoded.get(f);
                assertEquals(10, picture.bitDepth());
                assertEquals(ChromaFormat.YUV422, picture.chromaFormat());
                assertEquals(MatrixCoefficients.BT709, picture.color().matrix());
                for (var y = 0; y < 6; y++) {
                    for (var x = 0; x < 20; x++) {
                        assertEquals(value(0, x, y, f, 10), picture.y()[y * picture.yStride() + x]);
                        if (x < 10) {
                            assertEquals(value(1, x, y, f, 10), picture.u()[y * picture.uvStride() + x]);
                            assertEquals(value(2, x, y, f, 10), picture.v()[y * picture.uvStride() + x]);
                        }
                    }
                }
                reencode(format, picture, demuxed.packetsOf(track).get(f).data(), Compression.NONE);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"uncv-yuv420.mp4", "uncv-rgba-zlib.mp4", "uncv-yuv422-10.mp4"})
        @DisplayName("decode to the same frames under every source behavior")
        void readerBehavior(String name) {
            com.github.auties00.smalt.testkit.container.ReaderAsserts.video("mp4/" + name, resource(name)).run();
        }

        /** Asserts that Smalt's encoder writes the frame of the sample, after decompression for a compressed one. */
        private static void reencode(UncompressedVideoFormat format, Picture picture, byte[] stored,
                                     Compression compression) {
            var packets = new ArrayList<Packet>();
            try (var encoder = new UncompressedVideoEncoder(format, picture.width(), picture.height(), RATE, null)) {
                encoder.encode(new VideoFrame(picture, 0, 1, true), packets::add);
            }
            var written = packets.getFirst().data();
            if (compression == Compression.ZLIB) {
                assertArrayEquals(inflate(stored, false), inflate(written, false));
            } else {
                assertArrayEquals(stored, written);
            }
        }
    }

    @Nested
    @DisplayName("other readers of Smalt's files")
    class Readers {
        @Test
        @DisplayName("ffprobe reads the uncv track, its size and every packet")
        void ffprobe() {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var format = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 10).withInterleave(Interleave.MULTI_Y)
                    .withAlignedComponents(true).withLittleEndian(true);
            var stream = encode(format, 30, 14, 4);
            var file = MuxSupport.save("uncv-ffprobe.mp4", mux(stream));
            var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-show_streams", "-show_packets", "-select_streams", "v:0", file.toString())), StandardCharsets.UTF_8);
            assertTrue(output.contains("codec_tag_string=uncv"), output);
            assertTrue(output.contains("width=30"), output);
            assertTrue(output.contains("height=14"), output);
            var sizes = output.lines().filter(line -> line.startsWith("size=")).toList();
            assertEquals(4, sizes.size(), output);
            for (var size : sizes) {
                assertEquals("size=" + format.frameSize(30, 14), size);
            }
        }

        @Test
        @DisplayName("MP4Box lists the uncv track without a container error")
        void mp4Box() {
            Oracle.assume(Oracle.Tool.MP4BOX);
            var stream = encode(UncompressedVideoFormat.ofRgb(8).withAlpha(true), 30, 14, 2);
            var file = MuxSupport.save("uncv-mp4box.mp4", mux(stream));
            com.github.auties00.smalt.testkit.validate.Validators.mp4Box(file);
            var result = Oracle.execute(Oracle.Tool.MP4BOX, Duration.ofMinutes(1), null,
                    List.of("-info", file.toAbsolutePath().toString()));
            var output = new String(result.stdout(), StandardCharsets.UTF_8) + result.stderr();
            assertEquals(0, result.exitCode(), output);
            assertTrue(output.contains("uncv"), output);
            assertTrue(output.contains("30") && output.contains("14"), output);
        }
    }

    @Nested
    @DisplayName("layouts the encoder does not write")
    class HandBuilt {
        static byte[] box(String type, byte[] payload) {
            var out = new ByteArrayOutputStream();
            u32(out, 8 + payload.length);
            out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(payload);
            return out.toByteArray();
        }

        static void u32(ByteArrayOutputStream out, long value) {
            out.write((int) (value >>> 24));
            out.write((int) (value >>> 16));
            out.write((int) (value >>> 8));
            out.write((int) value);
        }

        static byte[] cmpd(int... types) {
            var out = new ByteArrayOutputStream();
            u32(out, types.length);
            for (var type : types) {
                out.write(type >>> 8);
                out.write(type);
            }
            return box("cmpd", out.toByteArray());
        }

        /** A version 0 uncC box: components as (index, depth, format, align) quadruples. */
        static byte[] uncC(int[][] components, int sampling, int interleave, int blockSize, int flags, long pixelSize,
                           long rowAlign) {
            var out = new ByteArrayOutputStream();
            u32(out, 0);
            u32(out, 0);
            u32(out, components.length);
            for (var component : components) {
                out.write(component[0] >>> 8);
                out.write(component[0]);
                out.write(component[1] - 1);
                out.write(component[2]);
                out.write(component[3]);
            }
            out.write(sampling);
            out.write(interleave);
            out.write(blockSize);
            out.write(flags);
            u32(out, pixelSize);
            u32(out, rowAlign);
            u32(out, 0);
            u32(out, 0);
            u32(out, 0);
            return box("uncC", out.toByteArray());
        }

        static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        static VideoTrack track(int width, int height, byte[] config) {
            var builder = VideoTrack.builder().id(1).codec(Codec.UNCOMPRESSED_23001_17).timeBase(new Rational(1, 25))
                    .width(width).height(height);
            if (config != null) {
                builder.codecConfig(config);
            }
            return builder.build();
        }

        /** Muxes one frame of a configuration and returns the demuxed track and packet. */
        static List<Picture> muxAndDecode(int width, int height, byte[] config, byte[] frame) {
            var track = track(width, height, config);
            var demuxed = MuxSupport.demux(MuxSupport.toMemory(List.of(track), List.of(new Packet(1, frame, 0, 0, 1, true)),
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4())));
            var read = videoTrack(demuxed);
            assertArrayEquals(config, read.codecConfig().orElseThrow());
            return decode(read, demuxed.packetsOf(read));
        }

        @Test
        @DisplayName("the rgb3 profile of a version 1 uncC box, without cmpd")
        void profile() {
            var out = new ByteArrayOutputStream();
            u32(out, 1 << 24);
            out.writeBytes("rgb3".getBytes(StandardCharsets.US_ASCII));
            var config = box("uncC", out.toByteArray());
            var frame = new byte[4 * 2 * 3];
            for (var i = 0; i < frame.length; i++) {
                frame[i] = (byte) (i * 11);
            }
            var picture = (YuvPicture) muxAndDecode(4, 2, config, frame).getFirst();
            for (var i = 0; i < 8; i++) {
                var x = i % 4;
                var y = i / 4;
                assertEquals(frame[3 * i] & 0xFF, picture.v()[y * picture.uvStride() + x] & 0xFF, "R");
                assertEquals(frame[3 * i + 1] & 0xFF, picture.y()[y * picture.yStride() + x] & 0xFF, "G");
                assertEquals(frame[3 * i + 2] & 0xFF, picture.u()[y * picture.uvStride() + x] & 0xFF, "B");
            }
        }

        @Test
        @DisplayName("pixel padding and a padding component")
        void pixelPadding() {
            // Y'CbCr 4:4:4 pixels of four bytes: Y, padding, Cb, Cr, then one byte of pixel padding
            var config = concat(cmpd(1, 12, 2, 3), uncC(new int[][]{{0, 8, 0, 0}, {1, 8, 0, 0}, {2, 8, 0, 0},
                    {3, 8, 0, 0}}, 0, 1, 0, 0, 5, 0));
            var frame = new byte[3 * 2 * 5];
            for (var i = 0; i < 6; i++) {
                frame[5 * i] = (byte) (10 + i);
                frame[5 * i + 1] = (byte) 0xEE;
                frame[5 * i + 2] = (byte) (20 + i);
                frame[5 * i + 3] = (byte) (30 + i);
                frame[5 * i + 4] = (byte) 0xDD;
            }
            var picture = (YuvPicture) muxAndDecode(3, 2, config, frame).getFirst();
            for (var i = 0; i < 6; i++) {
                assertEquals(10 + i, picture.y()[i / 3 * picture.yStride() + i % 3]);
                assertEquals(20 + i, picture.u()[i / 3 * picture.uvStride() + i % 3]);
                assertEquals(30 + i, picture.v()[i / 3 * picture.uvStride() + i % 3]);
            }
        }

        @Test
        @DisplayName("the chroma siting of a cloc box, with and without a track color description")
        void chromaLocation() {
            var cloc = box("cloc", new byte[]{0, 0, 0, 0, 2});
            var config = concat(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8).toCodecConfig(), cloc);
            var frame = new byte[16 * 8 * 3 / 2];
            var plain = track(16, 8, config);
            var described = plain.toBuilder().color(ColorInfo.BT709_LIMITED).build();
            var sited = plain.toBuilder().color(ColorInfo.BT709_LIMITED.withChromaSiting(
                    com.github.auties00.smalt.image.ChromaSiting.BOTTOM)).build();
            var expected = new com.github.auties00.smalt.image.ChromaSiting[]{
                    com.github.auties00.smalt.image.ChromaSiting.TOP_LEFT,
                    com.github.auties00.smalt.image.ChromaSiting.TOP_LEFT,
                    com.github.auties00.smalt.image.ChromaSiting.BOTTOM};
            var matrices = new MatrixCoefficients[]{MatrixCoefficients.BT601, MatrixCoefficients.BT709,
                    MatrixCoefficients.BT709};
            var tracks = new VideoTrack[]{plain, described, sited};
            for (var i = 0; i < tracks.length; i++) {
                var picture = (YuvPicture) decode(tracks[i], List.of(new Packet(1, frame, 0, 0, 1, true))).getFirst();
                assertEquals(expected[i], picture.color().chromaSiting(), "track " + i);
                assertEquals(matrices[i], picture.color().matrix(), "track " + i);
            }
            assertArrayEquals(config, videoTrack(MuxSupport.demux(MuxSupport.toMemory(List.of(plain),
                    List.of(new Packet(1, frame, 0, 0, 1, true)), sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4()))))
                    .codecConfig().orElseThrow());
        }

        @Test
        @DisplayName("v210 as reversed little-endian blocks, against ffmpeg's encoder")
        void v210() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi",
                    "-i", "testsrc2=size=48x8:rate=1", "-frames:v", "1", "-pix_fmt", "yuv422p10le", "-c:v", "v210",
                    "-f", "rawvideo", "-"));
            var planar = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi",
                    "-i", "testsrc2=size=48x8:rate=1", "-frames:v", "1", "-pix_fmt", "yuv422p10le", "-f", "rawvideo",
                    "-"));
            var config = concat(cmpd(1, 2, 3), uncC(new int[][]{{1, 10, 0, 0}, {0, 10, 0, 0}, {2, 10, 0, 0},
                    {0, 10, 0, 0}}, 1, 5, 4, 0x20 | 0x10, 0, 128));
            var picture = (Yuv16Picture) muxAndDecode(48, 8, config, raw).getFirst();
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 48; x++) {
                    var at = 2 * (y * 48 + x);
                    assertEquals((planar[at] & 0xFF) | (planar[at + 1] & 0xFF) << 8, picture.y()[y * picture.yStride() + x]);
                }
            }
        }
    }

    @Nested
    @DisplayName("malformed and unsupported input")
    class Invalid {
        private static final byte[] CONFIG = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8).toCodecConfig();

        @Test
        @DisplayName("a packet shorter than its frame and a corrupt compressed frame are malformed")
        void packets() {
            try (var decoder = new UncompressedVideoDecoder(HandBuilt.track(16, 8, CONFIG))) {
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(1, new byte[16 * 8], 0, 0, 1, true), frame -> {
                        }));
                decoder.decode(new Packet(1, new byte[16 * 8 * 3 / 2 + 7], 0, 0, 1, true), frame -> {
                });
            }
            var zlib = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8).withCompression(Compression.ZLIB)
                    .toCodecConfig();
            try (var decoder = new UncompressedVideoDecoder(HandBuilt.track(16, 8, zlib))) {
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(1, new byte[]{1, 2, 3, 4, 5}, 0, 0, 1, true), frame -> {
                        }));
                var empty = new java.util.zip.Deflater();
                empty.setInput(new byte[10]);
                empty.finish();
                var buffer = new byte[64];
                var length = empty.deflate(buffer);
                empty.end();
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(1, Arrays.copyOf(buffer, length), 0, 0, 1, true), frame -> {
                        }));
            }
        }

        @Test
        @DisplayName("a compressed frame expanding far beyond its layout is inflated without being held, and rejected "
                + "past the buffer limit")
        void bomb() {
            var deflater = new java.util.zip.Deflater(9);
            var zeros = new byte[1 << 20];
            var out = new ByteArrayOutputStream();
            var buffer = new byte[1 << 16];
            for (var i = 0; i < 64; i++) {
                deflater.setInput(zeros);
                while (!deflater.needsInput()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            var config = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8).withCompression(Compression.ZLIB)
                    .toCodecConfig();
            var frames = new ArrayList<VideoFrame>();
            var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            try (var decoder = new UncompressedVideoDecoder(HandBuilt.track(16, 8, config))) {
                var before = threads.getCurrentThreadAllocatedBytes();
                decoder.decode(new Packet(1, out.toByteArray(), 0, 0, 1, true), frames::add);
                var allocated = threads.getCurrentThreadAllocatedBytes() - before;
                assertTrue(allocated < 1 << 20, "allocated " + allocated + " bytes for a 192-byte frame");
            }
            var picture = (YuvPicture) frames.getFirst().picture();
            for (var i = 0; i < 16 * 8; i++) {
                assertEquals(0, picture.y()[i / 16 * picture.yStride() + i % 16]);
            }
            // 64 MiB of inflated data against a 32 MiB buffer limit
            var limited = com.github.auties00.smalt.video.VideoDecoderOptions.builder()
                    .limits(com.github.auties00.smalt.MediaLimits.builder().maxBytes(32L << 20).build()).build();
            try (var decoder = new UncompressedVideoDecoder(HandBuilt.track(16, 8, config), limited)) {
                var before = threads.getCurrentThreadAllocatedBytes();
                assertThrows(MediaException.LimitExceeded.class,
                        () -> decoder.decode(new Packet(1, out.toByteArray(), 0, 0, 1, true), frame -> {
                        }));
                var allocated = threads.getCurrentThreadAllocatedBytes() - before;
                assertTrue(allocated < 1 << 20, "allocated " + allocated + " bytes rejecting a 192-byte frame");
            }
        }

        @Test
        @DisplayName("missing, truncated and inconsistent configurations")
        void configurations() {
            assertThrows(MediaException.Malformed.class, () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8, null)));
            assertThrows(MediaException.Malformed.class,
                    () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8, HandBuilt.cmpd(1, 2, 3))));
            assertThrows(MediaException.Malformed.class,
                    () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8, Arrays.copyOf(CONFIG, CONFIG.length - 3))));
            assertThrows(MediaException.Malformed.class, () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8,
                    HandBuilt.concat(CONFIG, new byte[]{1, 2, 3}))));
            assertThrows(MediaException.Malformed.class, () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8,
                    HandBuilt.concat(HandBuilt.cmpd(1), HandBuilt.uncC(new int[][]{{3, 8, 0, 0}}, 0, 0, 0, 0, 0, 0)))));
            assertThrows(MediaException.Malformed.class, () -> new UncompressedVideoDecoder(HandBuilt.track(0, 8,
                    CONFIG)));
            assertThrows(MediaException.Unsupported.class, () -> new UncompressedVideoDecoder(HandBuilt.track(16, 8,
                    HandBuilt.concat(HandBuilt.cmpd(0), HandBuilt.uncC(new int[][]{{0, 8, 4, 0}}, 0, 0, 0, 0, 0, 0)))));
            assertThrows(MediaException.LimitExceeded.class, () -> new UncompressedVideoDecoder(HandBuilt.track(60_000,
                    60_000, CONFIG)));
            assertThrows(IllegalArgumentException.class, () -> new UncompressedVideoDecoder(VideoTrack.builder().id(1)
                    .codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25)).width(16).height(8).build()));
        }

        @Test
        @DisplayName("the muxer refuses a track without a well formed configuration")
        void muxer() {
            for (var config : new byte[][]{null, new byte[0], HandBuilt.cmpd(1, 2, 3), new byte[]{0, 0, 0, 9, 'u'},
                    HandBuilt.uncC(new int[][]{{0, 8, 0, 0}}, 0, 0, 0, 0, 0, 0)}) {
                try (var muxer = new Mp4Muxer(com.github.auties00.smalt.io.MediaSink.memory(), Mp4MuxerOptions.mp4())) {
                    assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(HandBuilt.track(16, 8, config)));
                }
            }
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.UNCOMPRESSED_23001_17));
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, Codec.UNCOMPRESSED_23001_17));
            assertTrue(FragmentedMp4Muxer.supports(Codec.UNCOMPRESSED_23001_17));
        }

        @Test
        @DisplayName("formats that cannot hold their components are rejected")
        void formats() {
            var y420 = UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8);
            assertThrows(IllegalArgumentException.class, () -> y420.withInterleave(Interleave.PIXEL));
            assertThrows(IllegalArgumentException.class, () -> y420.withInterleave(Interleave.ROW));
            assertThrows(IllegalArgumentException.class, () -> y420.withInterleave(Interleave.MULTI_Y));
            assertThrows(IllegalArgumentException.class,
                    () -> UncompressedVideoFormat.ofRgb(8).withInterleave(Interleave.MIXED));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 8)
                    .withInterleave(Interleave.MULTI_Y).withAlpha(true));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 10)
                    .withLittleEndian(true));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofRgb(0));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofRgb(17));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV440, 8));
            assertThrows(IllegalArgumentException.class, () -> UncompressedVideoFormat.ofYCbCr(ChromaFormat.MONOCHROME, 8));
            assertThrows(IllegalArgumentException.class, () -> y420.withTiles(0, 1));
            assertThrows(IllegalArgumentException.class, () -> y420.withRowAlignment(-1));
            assertThrows(IllegalArgumentException.class, () -> y420.withTiles(3, 1).frameSize(32, 16));
            assertThrows(IllegalArgumentException.class, () -> y420.withTiles(2, 1).frameSize(30, 16));
            assertThrows(IllegalArgumentException.class, () -> new UncompressedVideoEncoder(y420.withTiles(5, 1), 32, 16,
                    RATE, null));
            for (var format : List.of(y420, y420.withCompression(Compression.DEFLATE))) {
                var encoder = new UncompressedVideoEncoder(format, 16, 8, RATE, null);
                var picture = YuvPicture.allocate(16, 8, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                encoder.encode(new VideoFrame(picture, 0, 1, true), packet -> {
                });
                encoder.close();
                assertThrows(IllegalStateException.class, () -> encoder.encode(new VideoFrame(picture, 1, 1, true),
                        packet -> {
                        }), format.toString());
                assertThrows(IllegalArgumentException.class, () -> new UncompressedVideoEncoder(format, 16, 8, RATE,
                        null).encode(new VideoFrame(YuvPicture.allocate(8, 8, ChromaFormat.YUV420, false,
                        ColorInfo.BT601_LIMITED), 0, 1, true), packet -> {
                }));
            }
            assertEquals(32 * 16 * 3 / 2, y420.frameSize(32, 16));
            assertEquals(y420, UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8));
            assertEquals(y420.hashCode(), UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8).hashCode());
        }

        @Test
        @DisplayName("mutated files fail with media exceptions only")
        void mutations() {
            var file = resource("uncv-rgba-zlib.mp4");
            var random = new Random(7);
            for (var round = 0; round < 300; round++) {
                var mutated = file.clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    mutated[random.nextInt(mutated.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(MediaSource.of(mutated))) {
                    var demuxed = ContainerProbe.demuxAll(demuxer);
                    for (Track track : demuxed.tracks()) {
                        if (track instanceof VideoTrack video && video.codec() == Codec.UNCOMPRESSED_23001_17) {
                            decode(video, demuxed.packetsOf(video));
                        }
                    }
                } catch (MediaException expected) {
                    // a clean failure
                }
            }
        }
    }
}
