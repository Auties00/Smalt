package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public VP9 decoder: picture formats per profile, the rounding of 10 and 12-bit
 * frames to 8 bits, the rejection of 4:4:0 frames, timestamps and key frame flags, hidden frames
 * and superframes, the color description and the factory registration.
 */
@DisplayName("VP9 decoder")
class Vp9DecoderTest {
    static VideoTrack track(Optional<ColorInfo> color) {
        return VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 30)).color(color.orElse(null))
                .build();
    }

    static List<byte[]> packets(String name) throws IOException {
        return Vp9Vectors.packets(name, Files.readAllBytes(Vp9Vectors.stream(name)));
    }

    static List<VideoFrame> decodeAll(Vp9Decoder decoder, List<byte[]> packets) {
        var frames = new ArrayList<VideoFrame>();
        for (var i = 0; i < packets.size(); i++) {
            decoder.decode(new Packet(1, packets.get(i), 1000L * i, 1000L * i, 1000, false), frame -> {
                var picture = (YuvPicture) frame.picture();
                frames.add(new VideoFrame(copy(picture), frame.pts(), frame.duration(), frame.keyframe()));
            });
        }
        return frames;
    }

    static YuvPicture copy(YuvPicture picture) {
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false, picture
                .color());
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), copy.y(), y * copy.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), copy.u(), y * copy.uvStride(), picture.chromaWidth());
            System.arraycopy(picture.v(), y * picture.uvStride(), copy.v(), y * copy.uvStride(), picture.chromaWidth());
        }
        return copy;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"vp90-2-03-size-196x198.webm, YUV420", "vp91-2-04-yuv422.webm, YUV422", "vp91-2-04-yuv444.webm, YUV444",
            "vp92-2-20-10bit-yuv420.webm, YUV420", "vp93-2-20-12bit-yuv444.webm, YUV444"})
    @DisplayName("produces 8-bit pictures of the stream's chroma format and coded size")
    void formats(String name, ChromaFormat format) throws IOException {
        var packets = packets(name);
        var reference = new Vp9FrameDecoder(Vp9ConformanceTest.LIMITS, false);
        try (var decoder = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).build())) {
            var shown = 0;
            for (var i = 0; i < packets.size(); i++) {
                var data = packets.get(i);
                reference.decodePacket(data, 0, data.length);
                var frame = reference.shownFrame();
                var outputs = new ArrayList<VideoFrame>();
                decoder.decode(new Packet(1, data, i, i, 1, false), outputs::add);
                assertEquals(frame == null ? 0 : 1, outputs.size());
                if (frame != null) {
                    shown++;
                    var picture = assertInstanceOf(YuvPicture.class, outputs.getFirst().picture());
                    assertEquals(format, picture.chromaFormat());
                    assertEquals(frame.width, picture.width());
                    assertEquals(frame.height, picture.height());
                    assertEquals(frame.renderWidth, decoder.renderWidth());
                    assertEquals(frame.renderHeight, decoder.renderHeight());
                }
            }
            assertEquals(Vp9Vectors.expectedMd5(name).size(), shown);
        }
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @CsvSource({"vp92-2-20-10bit-yuv420.webm, 1", "vp92-2-20-12bit-yuv420.webm, 1", "vp93-2-20-10bit-yuv422.webm, 1",
            "vp92-2-20-10bit-yuv420.webm, 4", "vp93-2-20-12bit-yuv444.webm, 4"})
    @DisplayName("rounds high bit depth samples to 8 bits as documented, whatever the thread count")
    void highBitDepthRounding(String name, int threads) throws IOException {
        var packets = packets(name);
        try (var decoder = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).threads(threads).build())) {
            var reference = new Vp9FrameDecoder(Vp9ConformanceTest.LIMITS, false);
            for (var i = 0; i < Math.min(packets.size(), 6); i++) {
                var data = packets.get(i);
                reference.decodePacket(data, 0, data.length);
                var frame = reference.shownFrame();
                var outputs = new ArrayList<YuvPicture>();
                decoder.decode(new Packet(1, data, i, i, 1, false), out -> outputs.add(copy((YuvPicture) out.picture())));
                if (frame == null) {
                    assertTrue(outputs.isEmpty());
                    continue;
                }
                var picture = outputs.getFirst();
                var shift = frame.bitDepth - 8;
                var expected = new byte[picture.width() * picture.height()];
                var actual = new byte[expected.length];
                for (var y = 0; y < picture.height(); y++) {
                    for (var x = 0; x < picture.width(); x++) {
                        var sample = frame.planes16[0][y * frame.strides[0] + x] & 0xFFFF;
                        expected[y * picture.width() + x] = (byte) Math.min(255, (sample + (1 << (shift - 1))) >> shift);
                        actual[y * picture.width() + x] = picture.y()[y * picture.yStride() + x];
                    }
                }
                assertArrayEquals(expected, actual, "frame " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"vp92-2-20-10bit-yuv420.webm, 10", "vp92-2-20-12bit-yuv420.webm, 12", "vp93-2-20-10bit-yuv422.webm, 10"})
    @DisplayName("hands out high bit depth frames on request, whose reduction equals the default output")
    void highBitDepthOutput(String name, int depth) throws IOException {
        var packets = packets(name);
        try (var narrow = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).build());
             var wide = new Vp9Decoder(track(Optional.empty()),
                     VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).highBitDepth(true).build())) {
            for (var i = 0; i < Math.min(packets.size(), 6); i++) {
                var packet = new Packet(1, packets.get(i), i, i, 1, false);
                var expected = new ArrayList<YuvPicture>();
                narrow.decode(packet, out -> expected.add(copy((YuvPicture) out.picture())));
                var actual = new ArrayList<YuvPicture>();
                wide.decode(packet, out -> {
                    var picture = assertInstanceOf(Yuv16Picture.class, out.picture());
                    assertEquals(depth, picture.bitDepth());
                    actual.add(copy(ImageOps.toEightBit(picture)));
                });
                assertEquals(expected.size(), actual.size());
                for (var index = 0; index < expected.size(); index++) {
                    assertArrayEquals(planes(expected.get(index)), planes(actual.get(index)), "frame " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("keeps 8-bit frames in 8-bit pictures when high bit depth output is requested")
    void highBitDepthEightBitStream() throws IOException {
        var packets = packets("vp90-2-03-size-196x198.webm");
        try (var decoder = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).highBitDepth(true).build())) {
            var outputs = new ArrayList<VideoFrame>();
            decoder.decode(new Packet(1, packets.getFirst(), 0, 0, 1, true), outputs::add);
            assertInstanceOf(YuvPicture.class, outputs.getFirst().picture());
        }
    }

    private static byte[] planes(YuvPicture picture) {
        var out = new java.io.ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("rejects shown 4:4:0 frames as unsupported")
    void yuv440() throws IOException {
        var packets = packets("vp91-2-04-yuv440.webm");
        try (var decoder = new Vp9Decoder(track(Optional.empty()))) {
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> decoder.decode(new Packet(1, packets.getFirst(), 0, 0, 1, false), frame -> {
                    }));
            assertTrue(failure.getMessage().contains("4:4:0"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("carries the packet timestamps and flags key frames only")
    void timestampsAndKeyFrames() throws IOException {
        try (var decoder = new Vp9Decoder(track(Optional.empty()))) {
            var frames = decodeAll(decoder, packets("vp90-2-03-size-196x198.webm"));
            assertTrue(frames.getFirst().keyframe());
            var keys = frames.stream().filter(VideoFrame::keyframe).count();
            assertTrue(keys < frames.size(), "every frame flagged as a key frame");
            for (var i = 1; i < frames.size(); i++) {
                assertTrue(frames.get(i).pts() > frames.get(i - 1).pts());
                assertEquals(1000, frames.get(i).duration());
            }
        }
    }

    @Test
    @DisplayName("shows one frame per packet holding hidden frames in superframes")
    void hiddenFrames() throws IOException {
        var name = "vp90-2-22-svc_1280x720_3.ivf";
        var packets = packets(name);
        try (var decoder = new Vp9Decoder(track(Optional.empty()))) {
            var frames = decodeAll(decoder, packets);
            assertEquals(Vp9Vectors.expectedMd5(name).size(), frames.size());
            assertTrue(frames.size() <= packets.size());
        }
    }

    @Test
    @DisplayName("prefers the track's color description and otherwise maps the header's color space")
    void colors() throws IOException {
        var packets = packets("vp90-2-03-size-196x198.webm");
        var declared = new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.BT709);
        try (var decoder = new Vp9Decoder(track(Optional.of(declared)))) {
            var frames = decodeAll(decoder, packets.subList(0, 1));
            assertEquals(declared, ((YuvPicture) frames.getFirst().picture()).color());
        }
        try (var decoder = new Vp9Decoder(track(Optional.empty()))) {
            var frames = decodeAll(decoder, packets.subList(0, 1));
            var color = ((YuvPicture) frames.getFirst().picture()).color();
            assertEquals(MatrixCoefficients.BT601, color.matrix());
            assertEquals(ColorRange.LIMITED, color.range());
        }
    }

    @ParameterizedTest(name = "color space {0}")
    @CsvSource({"0, BT601, SMPTE170M", "1, BT470BG, SMPTE170M", "2, BT709, BT709", "3, BT601, SMPTE170M",
            "4, SMPTE240M, SMPTE240M", "5, BT2020_NCL, BT2020", "6, BT601, SMPTE170M"})
    @DisplayName("maps each header color space to the documented color description")
    void colorSpaces(int colorSpace, MatrixCoefficients matrix, ColorPrimaries primaries) {
        var picture = YuvPicture.allocate(64, 64, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        byte[] data;
        try (var encoder = new Vp9Encoder(64, 64, Vp9EncoderOptions.defaults())) {
            data = encoder.encodeFrame(new VideoFrame(picture, 0, 1, false)).orElseThrow().data().clone();
        }
        // A profile 0 key frame: 8 header bits, the 24-bit sync code, then the 3-bit color_space.
        assertEquals(0x49, data[1] & 0xFF);
        data[4] = (byte) ((data[4] & 0x1F) | colorSpace << 5);
        try (var decoder = new Vp9Decoder(track(Optional.empty()))) {
            var colors = new ArrayList<ColorInfo>();
            decoder.decode(new Packet(1, data, 0, 0, 1, true), frame -> colors.add(frame.picture() instanceof
                    YuvPicture yuv ? yuv.color() : null));
            assertEquals(matrix, colors.getFirst().matrix());
            assertEquals(primaries, colors.getFirst().primaries());
            assertEquals(ColorRange.LIMITED, colors.getFirst().range());
        }
    }

    @Test
    @DisplayName("is created by the video decoder factory")
    void factory() throws IOException {
        assertTrue(VideoDecoders.isSupported(Codec.VP9));
        var packets = packets("vp90-2-03-size-196x198.webm");
        try (var decoder = VideoDecoders.create(track(Optional.empty()), VideoDecoderOptions.builder().threads(4)
                .build())) {
            assertInstanceOf(Vp9Decoder.class, decoder);
            var count = new int[1];
            for (var packet : packets) {
                decoder.decode(new Packet(1, packet, 0, 0, 1, false), frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
            assertEquals(packets.size(), count[0]);
        }
    }

    @Test
    @DisplayName("decodes multithreaded frames for an interrupted caller and keeps its interrupt status")
    void interrupted() throws IOException {
        var packets = packets("vp90-2-08-tile_1x8.webm");
        List<VideoFrame> expected;
        try (var decoder = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).threads(1).build())) {
            expected = decodeAll(decoder, packets);
        }
        var actual = new ArrayList<VideoFrame>();
        try (var decoder = new Vp9Decoder(track(Optional.empty()),
                VideoDecoderOptions.builder().limits(Vp9ConformanceTest.LIMITS).threads(4).build())) {
            for (var i = 0; i < packets.size(); i++) {
                Thread.currentThread().interrupt();
                try {
                    decoder.decode(new Packet(1, packets.get(i), 1000L * i, 1000L * i, 1000, false), frame -> actual
                            .add(new VideoFrame(copy((YuvPicture) frame.picture()), frame.pts(), frame.duration(),
                                    frame.keyframe())));
                    assertTrue(Thread.currentThread().isInterrupted(), "packet " + i + " cleared the interrupt");
                } finally {
                    Thread.interrupted();
                }
            }
        }
        assertEquals(expected.size(), actual.size());
        for (var i = 0; i < expected.size(); i++) {
            var want = (YuvPicture) expected.get(i).picture();
            var got = (YuvPicture) actual.get(i).picture();
            assertArrayEquals(want.y(), got.y(), "frame " + i);
            assertArrayEquals(want.u(), got.u(), "frame " + i);
            assertArrayEquals(want.v(), got.v(), "frame " + i);
        }
    }

    @Test
    @DisplayName("rejects tracks of other codecs")
    void otherCodec() {
        var track = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).build();
        assertThrows(IllegalArgumentException.class, () -> new Vp9Decoder(track));
    }
}
