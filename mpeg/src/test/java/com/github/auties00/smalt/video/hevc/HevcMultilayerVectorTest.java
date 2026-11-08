package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the multi-layer streams the SHM reference encoder made, kept with the test
 * ({@code multilayer/multilayer.tsv}, written by {@code tools/testdata/hevc-multilayer/make-vectors.py}), and compares
 * every output picture of every layer with SHM's decode: spatial scalability at 2x and 1.5x, quality and bit depth
 * scalability, two MV-HEVC views and an alpha auxiliary layer. It also checks the layer side data, the attached alpha
 * planes and the decoder's robustness to truncated, damaged and incomplete multi-layer streams.
 */
class HevcMultilayerVectorTest {
    record Layer(String name, int layer, int width, int height, int frames, String streamSha256, List<String> md5s) {
        @Override
        public String toString() {
            return name + " layer " + layer;
        }
    }

    static List<Layer> layers() {
        try (var input = HevcMultilayerVectorTest.class.getResourceAsStream("multilayer/multilayer.tsv")) {
            var result = new ArrayList<Layer>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                result.add(new Layer(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                        Integer.parseInt(columns[3]), Integer.parseInt(columns[4]), columns[5],
                        List.of(columns[6].split(","))));
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Arguments> layersOnThreads() {
        // the external AVC base layer of the hybrid stream is not HEVC; AvcBase decodes that stream
        return layers().stream().filter(layer -> !layer.name().contains("avcbase"))
                .flatMap(layer -> Stream.of(Arguments.of(layer, 1), Arguments.of(layer, 4)));
    }

    static Layer layer(String name, int layer) {
        return layers().stream().filter(entry -> entry.name().equals(name) && entry.layer() == layer).findFirst()
                .orElseThrow();
    }

    static byte[] stream(String name) {
        try (var input = HevcMultilayerVectorTest.class.getResourceAsStream("multilayer/" + name + ".hevc")) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes a stream fed one NAL unit per packet, copying each frame, whose samples the decoder recycles. */
    static List<VideoFrame> decode(byte[] stream, HevcDecoderOptions options) {
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(new VideoFrame(snapshot(frame.picture()), frame.pts(),
                    frame.duration(), frame.keyframe(), frame.sideData()));
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static Picture snapshot(Picture picture) {
        switch (picture) {
            case YuvPicture p -> {
                var copy = YuvPicture.allocate(p.width(), p.height(), p.chromaFormat(), p.hasAlpha(), p.color());
                rows(p.y(), p.yStride(), copy.y(), copy.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    rows(p.u(), p.uvStride(), copy.u(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                    rows(p.v(), p.uvStride(), copy.v(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                if (p.hasAlpha()) {
                    rows(p.alpha(), p.alphaStride(), copy.alpha(), copy.alphaStride(), p.width(), p.height());
                }
                return copy;
            }
            case Yuv16Picture p -> {
                var copy = Yuv16Picture.allocate(p.width(), p.height(), p.chromaFormat(), p.bitDepth(), p.hasAlpha(),
                        p.color());
                rows(p.y(), p.yStride(), copy.y(), copy.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    rows(p.u(), p.uvStride(), copy.u(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                    rows(p.v(), p.uvStride(), copy.v(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                if (p.hasAlpha()) {
                    rows(p.alpha(), p.alphaStride(), copy.alpha(), copy.alphaStride(), p.width(), p.height());
                }
                return copy;
            }
            case RgbaPicture p -> {
                return RgbaPicture.of(p.width(), p.height(), p.stride(), p.pixels().clone(), p.hasAlpha());
            }
            default -> throw new AssertionError("unexpected picture " + picture);
        }
    }

    private static void rows(Object source, int sourceStride, Object target, int targetStride, int width, int height) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(source, row * sourceStride, target, row * targetStride, width);
        }
    }

    static HevcDecoderOptions.Builder options(int threads) {
        return HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).threads(threads);
    }

    static int layerOf(VideoFrame frame) {
        return frame.sideData().layer().map(VideoLayer::id).orElse(0);
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest(name = "{0} on {1} threads")
    @MethodSource("layersOnThreads")
    @DisplayName("decodes each layer like the SHM reference decoder, with the output layer set that outputs it")
    void shm(Layer vector, int threads) {
        var stream = stream(vector.name());
        assertEquals(vector.streamSha256(), sha256(stream), vector + " stream");
        var frames = decode(stream, options(threads).outputLayerSet(vector.layer()).alpha(false).build()).stream()
                .filter(frame -> layerOf(frame) == vector.layer()).toList();
        assertEquals(vector.frames(), frames.size(), vector + " frames");
        for (var i = 0; i < frames.size(); i++) {
            var picture = frames.get(i).picture();
            assertEquals(vector.width(), picture.width(), vector + " frame " + i + " width");
            assertEquals(vector.height(), picture.height(), vector + " frame " + i + " height");
            assertEquals(vector.md5s().get(i), md5(HevcTestStreams.raw(picture)), vector + " frame " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shvc-2x-ld", "shvc-1.5x-ra", "shvc-snr-8-10", "mvhevc-ld", "alpha-ld"})
    @DisplayName("the last output layer set is the second of a two-layer stream; the default decodes the base layer")
    void outputLayerSets(String name) {
        var stream = stream(name);
        var last = decode(stream, options(1).lastOutputLayerSet().alpha(false).build());
        var second = decode(stream, options(1).outputLayerSet(1).alpha(false).build());
        assertEquals(second.size(), last.size(), name);
        for (var i = 0; i < last.size(); i++) {
            assertArrayEquals(HevcTestStreams.raw(second.get(i).picture()), HevcTestStreams.raw(last.get(i).picture()));
            assertEquals(second.get(i).sideData(), last.get(i).sideData());
        }
        // an output layer set past the last one selects the last one
        var beyond = decode(stream, options(1).outputLayerSet(9).alpha(false).build());
        assertEquals(last.size(), beyond.size(), name);
        var base = decode(stream, options(1).alpha(false).build());
        var expected = layer(name, 0);
        assertEquals(expected.frames(), base.size(), name);
        for (var i = 0; i < base.size(); i++) {
            assertTrue(base.get(i).sideData().layer().isEmpty(), "a single-layer decode names no layer");
            assertEquals(expected.md5s().get(i), md5(HevcTestStreams.raw(base.get(i).picture())), name + " frame " + i);
        }
    }

    @Nested
    @DisplayName("external AVC base layer")
    class AvcBase {
        private static final String NAME = "shvc-avcbase-2x-ld";

        static List<byte[]> avcPictures() {
            var base = layer(NAME, 0);
            byte[] avc;
            try (var input = HevcMultilayerVectorTest.class.getResourceAsStream("multilayer/" + NAME + ".264")) {
                avc = input.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertEquals(base.streamSha256(), sha256(avc), "AVC stream");
            var pictures = new ArrayList<byte[]>();
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
            try (var decoder = new H264Decoder(track)) {
                Consumer<VideoFrame> sink = frame -> pictures.add(HevcTestStreams.raw(frame.picture()));
                for (var nal : HevcTestStreams.splitAnnexB(avc)) {
                    decoder.decode(HevcTestStreams.packet(nal), sink);
                }
                decoder.flush(sink);
            }
            assertEquals(base.frames(), pictures.size(), "AVC pictures");
            for (var i = 0; i < pictures.size(); i++) {
                assertEquals(base.md5s().get(i), md5(pictures.get(i)), "AVC picture " + i + " against ffmpeg");
            }
            return pictures;
        }

        @ParameterizedTest(name = "on {0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("decodes the enhancement layer over the pictures of an AVC stream like SHM")
        void enhancement(int threads) {
            var pictures = avcPictures();
            var expected = layer(NAME, 1);
            var stream = stream(NAME);
            assertEquals(expected.streamSha256(), sha256(stream), "HEVC stream");
            var outputs = new ArrayList<byte[]>();
            var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, 1, false, frame -> {
                if (frame.layerId == 1) {
                    outputs.add(planes(frame));
                }
            });
            var next = new int[1];
            engine.externalBaseLayer((format, pocLsb) -> {
                if (next[0] >= pictures.size()) {
                    return null;
                }
                var index = next[0]++;
                var frame = new HevcFrame(format.width(), format.height(), format.chromaFormatIdc(), true);
                var picture = pictures.get(index);
                var luma = format.width() * format.height();
                var chroma = luma / 4;
                System.arraycopy(picture, 0, frame.y8, 0, luma);
                System.arraycopy(picture, luma, frame.cb8, 0, chroma);
                System.arraycopy(picture, luma + chroma, frame.cr8, 0, chroma);
                return new HevcDecoderEngine.ExternalBaseLayer.Picture(frame, index == 0, HevcNal.IDR_W_RADL);
            });
            try {
                for (var nal : HevcTestStreams.nalUnits(stream)) {
                    engine.decodeNal(nal, 0, nal.length);
                }
                engine.flush();
            } finally {
                engine.close();
            }
            assertEquals(expected.frames(), outputs.size(), "enhancement pictures");
            for (var i = 0; i < outputs.size(); i++) {
                assertEquals(expected.md5s().get(i), md5(outputs.get(i)), "enhancement picture " + i);
            }
        }

        @Test
        @DisplayName("the public decoder reports a stream whose base layer is coded outside it as unsupported")
        void unsupported() {
            var stream = stream(NAME);
            assertThrows(MediaException.Unsupported.class, () -> decode(stream, options(1).outputLayerSet(1).build()));
        }

        private static byte[] planes(HevcFrame frame) {
            var luma = frame.width * frame.height;
            var chroma = frame.chromaWidth * frame.chromaHeight;
            var out = new byte[luma + 2 * chroma];
            System.arraycopy(frame.y8, 0, out, 0, luma);
            System.arraycopy(frame.cb8, 0, out, luma, chroma);
            System.arraycopy(frame.cr8, 0, out, luma + chroma, chroma);
            return out;
        }
    }

    @Nested
    @DisplayName("in MP4")
    class InMp4 {
        /** Muxes a multi-layer stream to MP4: the parameter sets of every layer in the configuration, one sample per
         * access unit. */
        static byte[] mp4(byte[] stream, int width, int height) {
            var sets = new ArrayList<byte[]>();
            var units = new ArrayList<List<byte[]>>();
            var current = new ArrayList<byte[]>();
            var slice = false;
            for (var nal : HevcTestStreams.nalUnits(stream)) {
                var type = HevcTestStreams.type(nal);
                if (type >= 32 && type <= 34) {
                    if (sets.stream().noneMatch(set -> Arrays.equals(set, nal))) {
                        sets.add(nal);
                    }
                    continue;
                }
                var baseLayer = (nal[0] & 1) == 0 && (nal[1] & 0xF8) == 0;
                if (slice && baseLayer && (type == 35 || type == 39 || type < 32 && (nal[2] & 0x80) != 0)) {
                    units.add(current);
                    current = new ArrayList<>();
                    slice = false;
                }
                current.add(nal);
                slice |= type < 32;
            }
            units.add(current);
            var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).width(width)
                    .height(height).codecConfig(HevcTestStreams.hvcC(sets)).build();
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink)) {
                muxer.addTrack(track);
                for (var i = 0; i < units.size(); i++) {
                    muxer.writePacket(new Packet(1, HevcTestStreams.lengthPrefixed(units.get(i)), i * 3600L, i * 3600L,
                            3600, i == 0));
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }

        @Test
        @DisplayName("a multiview stream muxed with an lhvC record decodes both views, as ffmpeg decodes them")
        void multiview() throws IOException {
            var file = mp4(stream("mvhevc-ld"), 128, 96);
            assertTrue(new String(file, StandardCharsets.ISO_8859_1).contains("lhvC"), "the sample entry has lhvC");
            var byLayer = new java.util.TreeMap<Integer, List<String>>();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                var options = options(1).outputLayerSet(1).build();
                try (var decoder = new HevcDecoder(track, options)) {
                    Consumer<VideoFrame> sink = frame -> byLayer.computeIfAbsent(layerOf(frame), _ -> new ArrayList<>())
                            .add(md5(HevcTestStreams.raw(frame.picture())));
                    Packet packet;
                    while ((packet = demuxer.read()) != null) {
                        decoder.decode(packet, sink);
                    }
                    decoder.flush(sink);
                }
            }
            for (var layer = 0; layer < 2; layer++) {
                assertEquals(layer("mvhevc-ld", layer).md5s(), byLayer.get(layer), "view " + layer);
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = Scratch.directory("hevc-multilayer").resolve("mvhevc-ld.mp4");
            Files.write(path, file);
            for (var view = 0; view < 2; view++) {
                // ffmpeg names the views by their ViewId, equal to the layer identifiers here
                var decoded = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                        path.toString(), "-map", "0:v:0:view:" + view, "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
                var size = 128 * 96 * 3 / 2;
                var md5s = new ArrayList<String>();
                for (var start = 0; start + size <= decoded.length; start += size) {
                    md5s.add(md5(Arrays.copyOfRange(decoded, start, start + size)));
                }
                assertEquals(layer("mvhevc-ld", view).md5s(), md5s, "ffmpeg's view " + view);
            }
        }

        @Test
        @DisplayName("an alpha layer muxed with an lhvC record is attached to the frames of the MP4 track")
        void alpha() {
            var file = mp4(stream("alpha-ld"), 128, 96);
            var frames = new ArrayList<Boolean>();
            var colors = new ArrayList<String>();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                try (var decoder = new HevcDecoder(track)) {
                    Consumer<VideoFrame> sink = frame -> {
                        frames.add(frame.picture().hasAlpha());
                        colors.add(md5(HevcTestStreams.raw(frame.picture())));
                    };
                    Packet packet;
                    while ((packet = demuxer.read()) != null) {
                        decoder.decode(packet, sink);
                    }
                    decoder.flush(sink);
                }
            }
            assertEquals(List.of(true, true, true, true, true, true), frames);
            assertEquals(layer("alpha-ld", 0).md5s(), colors);
        }
    }

    @Nested
    @DisplayName("multiview")
    class Multiview {
        @Test
        @DisplayName("outputs the two views of each access unit in layer order, naming their layers and views")
        void views() {
            var frames = decode(stream("mvhevc-ld"), options(1).outputLayerSet(1).build());
            assertEquals(12, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var layer = frames.get(i).sideData().layer().orElseThrow();
                assertEquals(new VideoLayer(i % 2, i % 2, VideoLayer.Type.PRIMARY), layer, "frame " + i);
                if (i % 2 == 1) {
                    assertEquals(frames.get(i - 1).pts(), frames.get(i).pts(), "the views of an access unit");
                }
            }
        }
    }

    @Nested
    @DisplayName("alpha auxiliary layer")
    class Alpha {
        @Test
        @DisplayName("outputs the alpha layer as a picture of its own when alpha attachment is off")
        void separate() {
            var frames = decode(stream("alpha-ld"), options(1).outputLayerSet(1).alpha(false).build());
            var alpha = frames.stream().filter(frame -> layerOf(frame) == 1).toList();
            assertEquals(6, alpha.size());
            for (var frame : alpha) {
                assertEquals(VideoLayer.Type.ALPHA, frame.sideData().layer().orElseThrow().type());
                assertFalse(frame.picture().hasAlpha());
            }
        }

        @ParameterizedTest(name = "on {0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("attaches the alpha layer to the primary pictures, mapped through the alpha channel information")
        void attached(int threads) {
            var stream = stream("alpha-ld");
            var lumas = decode(stream, options(1).outputLayerSet(1).alpha(false).build()).stream()
                    .filter(frame -> layerOf(frame) == 1).map(VideoFrame::picture).toList();
            var frames = decode(stream, options(threads).build());
            var primary = layer("alpha-ld", 0);
            assertEquals(primary.frames(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var picture = assertYuv(frames.get(i).picture());
                assertTrue(picture.hasAlpha(), "frame " + i + " has alpha");
                assertEquals(primary.md5s().get(i), md5(HevcTestStreams.raw(picture)), "frame " + i + " color");
                var luma = assertYuv(lumas.get(i));
                var expected = new byte[picture.width() * picture.height()];
                var actual = new byte[expected.length];
                for (var y = 0; y < picture.height(); y++) {
                    for (var x = 0; x < picture.width(); x++) {
                        // transparent 16 and opaque 235, rescaled to the full 8-bit range with rounding
                        var sample = luma.y()[y * luma.yStride() + x] & 0xFF;
                        expected[y * picture.width() + x] = (byte) Math.clamp(
                                Math.floorDiv(2L * (sample - 16) * 255 + 219, 2L * 219), 0, 255);
                        actual[y * picture.width() + x] = picture.alpha()[y * picture.alphaStride() + x];
                    }
                }
                assertArrayEquals(expected, actual, "frame " + i + " alpha");
            }
        }

        @Test
        @DisplayName("decodes the primary layer alone when alpha attachment is off")
        void off() {
            var frames = decode(stream("alpha-ld"), options(1).alpha(false).build());
            assertEquals(6, frames.size());
            for (var frame : frames) {
                assertFalse(frame.picture().hasAlpha());
            }
        }

        /** The alpha samples of the alpha layer decoded as frames of its own, one array per access unit. */
        private static List<byte[]> alphaLumas(byte[] stream) {
            var result = new ArrayList<byte[]>();
            for (var frame : decode(stream, options(1).outputLayerSet(1).alpha(false).build())) {
                if (layerOf(frame) == 1) {
                    var luma = assertYuv(frame.picture());
                    var samples = new byte[luma.width() * luma.height()];
                    for (var y = 0; y < luma.height(); y++) {
                        System.arraycopy(luma.y(), y * luma.yStride(), samples, y * luma.width(), luma.width());
                    }
                    result.add(samples);
                }
            }
            return result;
        }

        /** The alpha plane of a frame, row after row. */
        private static byte[] alphaOf(YuvPicture picture) {
            var alpha = new byte[picture.width() * picture.height()];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.alpha(), y * picture.alphaStride(), alpha, y * picture.width(), picture.width());
            }
            return alpha;
        }

        /**
         * Replaces the alpha channel information SEI message of the alpha layer of a stream by one with other
         * values (F.14.2.8), 8-bit.
         */
        private static byte[] withAlphaInfo(byte[] stream, int use, int transparent, int opaque, boolean increment,
                                            boolean clip, boolean clipType) {
            var bits = new StringBuilder();
            append(bits, 0, 1);
            append(bits, use, 3);
            append(bits, 0, 3);
            append(bits, transparent, 9);
            append(bits, opaque, 9);
            append(bits, increment ? 1 : 0, 1);
            append(bits, clip ? 1 : 0, 1);
            if (clip) {
                append(bits, clipType ? 1 : 0, 1);
            }
            if (bits.length() % 8 != 0) {
                // payload_bit_equal_to_one and the alignment zeros of an SEI payload
                bits.append('1');
                while (bits.length() % 8 != 0) {
                    bits.append('0');
                }
            }
            var rbsp = new java.io.ByteArrayOutputStream();
            rbsp.write(165);
            rbsp.write(bits.length() / 8);
            for (var i = 0; i < bits.length(); i += 8) {
                rbsp.write(Integer.parseInt(bits.substring(i, i + 8), 2));
            }
            rbsp.write(0x80);
            var nal = new java.io.ByteArrayOutputStream();
            nal.writeBytes(new byte[]{0, 0, 0, 1, 0x4E, 0x09});
            var zeros = 0;
            for (var value : rbsp.toByteArray()) {
                if (zeros >= 2 && (value & 0xFF) <= 3) {
                    nal.write(3);
                    zeros = 0;
                }
                nal.write(value);
                zeros = value == 0 ? zeros + 1 : 0;
            }
            var replaced = 0;
            var units = new ArrayList<byte[]>();
            for (var unit : HevcTestStreams.splitAnnexB(stream)) {
                var start = 0;
                while (unit[start] == 0) {
                    start++;
                }
                start++;
                if (Robustness.header(unit, 0) == 39 && Robustness.header(unit, 1) == 1 && (unit[start + 2] & 0xFF) == 165) {
                    units.add(nal.toByteArray());
                    replaced++;
                } else {
                    units.add(unit);
                }
            }
            assertTrue(replaced > 0, "the stream carries an alpha channel information message");
            return Robustness.join(units);
        }

        private static void append(StringBuilder bits, int value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                bits.append((value >> i) & 1);
            }
        }

        @Test
        @DisplayName("increments and clips the alpha samples as the alpha channel information says (clip type 1)")
        void incrementAndClip() {
            var stream = withAlphaInfo(stream("alpha-ld"), 0, 0, 128, true, true, true);
            var lumas = alphaLumas(stream);
            var frames = decode(stream, options(1).build());
            assertEquals(lumas.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var luma = lumas.get(i);
                var expected = new byte[luma.length];
                for (var k = 0; k < luma.length; k++) {
                    // samples above Min(transparent, opaque) are incremented, then clipped to [0, 128], then
                    // rescaled from [0, 128] to [0, 255] with rounding
                    var sample = luma[k] & 0xFF;
                    var value = Math.min(128, sample > 0 ? sample + 1 : sample);
                    expected[k] = (byte) Math.floorDiv(2 * value * 255 + 128, 2 * 128);
                }
                assertArrayEquals(expected, alphaOf(assertYuv(frames.get(i).picture())), "frame " + i + " alpha");
            }
        }

        @Test
        @DisplayName("an opaque value below the transparent value inverts the alpha")
        void inverted() {
            var stream = withAlphaInfo(stream("alpha-ld"), 0, 235, 16, false, false, false);
            var lumas = alphaLumas(stream);
            var frames = decode(stream, options(1).build());
            assertEquals(lumas.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var luma = lumas.get(i);
                var expected = new byte[luma.length];
                for (var k = 0; k < luma.length; k++) {
                    var scaled = Math.floor(((luma[k] & 0xFF) - 235) * 255.0 / (16 - 235) + 0.5);
                    expected[k] = (byte) Math.clamp((long) scaled, 0, 255);
                }
                assertArrayEquals(expected, alphaOf(assertYuv(frames.get(i).picture())), "frame " + i + " alpha");
            }
        }

        @ParameterizedTest(name = "on {0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("color premultiplied by the alpha is divided back into straight-alpha RGBA frames")
        void premultiplied(int threads) {
            var straight = decode(withAlphaInfo(stream("alpha-ld"), 0, 16, 235, false, false, false),
                    options(threads).build());
            var frames = decode(withAlphaInfo(stream("alpha-ld"), 1, 16, 235, false, false, false),
                    options(threads).build());
            assertEquals(straight.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                if (!(frames.get(i).picture() instanceof RgbaPicture rgba)) {
                    throw new AssertionError("frame " + i + " is not RGBA: " + frames.get(i).picture());
                }
                var expected = com.github.auties00.smalt.image.ops.ImageOps.toRgba(straight.get(i).picture());
                var pixels = new byte[expected.width() * expected.height() * 4];
                for (var y = 0; y < expected.height(); y++) {
                    System.arraycopy(expected.pixels(), y * expected.stride(), pixels, y * expected.width() * 4,
                            expected.width() * 4);
                }
                for (var k = 0; k < pixels.length; k += 4) {
                    var alpha = pixels[k + 3] & 0xFF;
                    for (var c = 0; c < 3 && alpha < 255; c++) {
                        pixels[k + c] = (byte) (alpha == 0 ? 0
                                : Math.min(255, ((pixels[k + c] & 0xFF) * 510 + alpha) / (2 * alpha)));
                    }
                }
                assertEquals(expected.width(), rgba.width());
                var actual = new byte[pixels.length];
                for (var y = 0; y < rgba.height(); y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), actual, y * rgba.width() * 4, rgba.width() * 4);
                }
                assertArrayEquals(pixels, actual, "frame " + i);
            }
        }

        @Test
        @DisplayName("an access unit without its alpha picture keeps the alpha of the one before")
        void persistence() {
            var units = new ArrayList<>(HevcTestStreams.splitAnnexB(stream("alpha-ld")));
            var last = -1;
            for (var i = 0; i < units.size(); i++) {
                if (Robustness.header(units.get(i), 1) == 1 && Robustness.header(units.get(i), 0) < 32) {
                    last = i;
                }
            }
            // the alpha slice of the last access unit and its suffix SEI message (the picture hash)
            units.remove(last);
            if (last < units.size() && Robustness.header(units.get(last), 0) == 40
                    && Robustness.header(units.get(last), 1) == 1) {
                units.remove(last);
            }
            var frames = decode(Robustness.join(units), options(1).build());
            var complete = decode(stream("alpha-ld"), options(1).build());
            assertEquals(complete.size(), frames.size());
            var count = frames.size();
            for (var i = 0; i < count - 1; i++) {
                assertArrayEquals(alphaOf(assertYuv(complete.get(i).picture())), alphaOf(assertYuv(frames.get(i).picture())),
                        "frame " + i);
            }
            assertArrayEquals(alphaOf(assertYuv(complete.get(count - 2).picture())),
                    alphaOf(assertYuv(frames.get(count - 1).picture())), "the last frame keeps the alpha before it");
            assertEquals(md5(HevcTestStreams.raw(complete.get(count - 1).picture())),
                    md5(HevcTestStreams.raw(frames.get(count - 1).picture())), "the color of the last frame");
        }

        private static YuvPicture assertYuv(Picture picture) {
            if (picture instanceof YuvPicture yuv) {
                return yuv;
            }
            throw new AssertionError("expected an 8-bit picture, got " + picture);
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        static Stream<String> names() {
            return Stream.of("shvc-2x-ld", "shvc-1.5x-ra", "shvc-snr-8-10", "mvhevc-ld", "alpha-ld");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("names")
        @DisplayName("truncated and damaged streams fail only with media exceptions")
        void damaged(String name) {
            var stream = stream(name);
            var random = new Random(name.hashCode());
            assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
                for (var length = 0; length < stream.length; length += Math.max(1, stream.length / 60)) {
                    attempt(Arrays.copyOf(stream, length), random.nextInt(2) + 1);
                }
                for (var round = 0; round < 120; round++) {
                    var mutated = stream.clone();
                    for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                        // the parameter sets come first, so half the rounds damage them
                        var limit = round % 2 == 0 ? Math.min(mutated.length, 400) : mutated.length;
                        mutated[random.nextInt(limit)] = (byte) random.nextInt(256);
                    }
                    attempt(mutated, round % 3 == 0 ? 4 : 1);
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("names")
        @DisplayName("a stream without the base layer or without its video parameter set outputs only intact pictures")
        void incomplete(String name) {
            var units = HevcTestStreams.splitAnnexB(stream(name));
            var withoutBase = units.stream().filter(unit -> header(unit, 1) != 0 || header(unit, 0) == 32).toList();
            var withoutVps = units.stream().filter(unit -> header(unit, 0) != 32).toList();
            for (var variant : List.of(withoutBase, withoutVps)) {
                var joined = join(variant);
                for (var threads : new int[]{1, 4}) {
                    var frames = attempt(joined, threads);
                    for (var frame : frames) {
                        assertTrue(frame.picture().width() > 0 && frame.picture().height() > 0);
                    }
                }
            }
            // without its video parameter set, the base layer still decodes on its own
            var base = attempt(join(withoutVps), 1);
            assertEquals(layer(name, 0).frames(), base.size(), name);
        }

        @ParameterizedTest(name = "on {0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("a multiview stream followed by a single layer stream outputs every picture of both")
        void spliced(int threads) throws IOException {
            byte[] single;
            try (var input = HevcMultilayerVectorTest.class.getResourceAsStream("hash/md5-420-8bit.hevc")) {
                single = input.readAllBytes();
            }
            var multiview = stream("mvhevc-ld");
            var options = options(threads).lastOutputLayerSet().build();
            var first = decode(multiview, options);
            var second = decode(single, options);
            var joined = new byte[multiview.length + single.length];
            System.arraycopy(multiview, 0, joined, 0, multiview.length);
            System.arraycopy(single, 0, joined, multiview.length, single.length);
            var frames = decode(joined, options);
            assertEquals(first.size() + second.size(), frames.size(), "every picture of both streams");
            for (var i = 0; i < frames.size(); i++) {
                var expected = i < first.size() ? first.get(i) : second.get(i - first.size());
                assertEquals(md5(HevcTestStreams.raw(expected.picture())), md5(HevcTestStreams.raw(frames.get(i)
                        .picture())), "frame " + i);
                assertEquals(expected.sideData().layer(), frames.get(i).sideData().layer(), "frame " + i + " layer");
            }
        }

        /** Returns the nal_unit_type (field 0) or the nuh_layer_id (field 1) of a NAL unit with its start code. */
        private static int header(byte[] unit, int field) {
            var start = 0;
            while (start < unit.length && unit[start] == 0) {
                start++;
            }
            start++;
            if (start + 1 >= unit.length) {
                return -1;
            }
            return field == 0 ? (unit[start] >> 1) & 0x3F : ((unit[start] & 1) << 5) | ((unit[start + 1] & 0xFF) >> 3);
        }

        private static byte[] join(List<byte[]> units) {
            var size = units.stream().mapToInt(unit -> unit.length).sum();
            var joined = new byte[size];
            var position = 0;
            for (var unit : units) {
                System.arraycopy(unit, 0, joined, position, unit.length);
                position += unit.length;
            }
            return joined;
        }

        private static List<VideoFrame> attempt(byte[] data, int threads) {
            var frames = new ArrayList<VideoFrame>();
            var options = HevcDecoderOptions.builder().threads(threads).lastOutputLayerSet().build();
            try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
                Consumer<VideoFrame> sink = frames::add;
                for (var nal : HevcTestStreams.splitAnnexB(data)) {
                    try {
                        decoder.decode(HevcTestStreams.packet(nal), sink);
                    } catch (MediaException exception) {
                        // a player skips the packet and goes on
                    }
                }
                try {
                    decoder.flush(sink);
                } catch (MediaException exception) {
                    // the pictures in flight are lost
                }
            }
            for (var frame : frames) {
                switch (frame.picture()) {
                    case YuvPicture yuv -> assertTrue(yuv.y().length >= yuv.width());
                    case Yuv16Picture yuv -> assertTrue(yuv.y().length >= yuv.width());
                    default -> throw new AssertionError("unexpected picture " + frame.picture());
                }
            }
            return frames;
        }
    }
}
