package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the Theora vectors of {@code src/test/resources/com/github/auties00/smalt/video/theora} (ffmpeg's libtheora
 * encodes of every pixel format, cropped sizes, qualities, bit rates and key frame intervals; see
 * {@code tools/testdata/theora-oracle/make-vectors.py}) and compares every picture with the MD5 of libtheora 1.2.0's
 * decode recorded in {@code expected.tsv}, also with dropped frames and with the first key frame missing. A mismatch
 * reports the first differing sample against ffmpeg's decode, which matches libtheora's, when ffmpeg is installed.
 */
@DisplayName("Theora decoder")
class TheoraDecoderTest {
    static byte[] resource(String name) {
        try (var in = TheoraDecoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Vector(String name, String variant, List<String> md5s) {
        @Override
        public String toString() {
            return variant.isEmpty() ? name : name + "#" + variant;
        }
    }

    static Stream<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("expected.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            var split = fields[0].indexOf('#');
            var name = split < 0 ? fields[0] : fields[0].substring(0, split);
            var variant = split < 0 ? "" : fields[0].substring(split + 1);
            vectors.add(new Vector(name, variant, List.of(fields[1].split(" "))));
        }
        return vectors.stream();
    }

    static Stream<String> names() {
        return vectors().filter(vector -> vector.variant().isEmpty()).map(Vector::name);
    }

    record Theora(VideoTrack track, List<Packet> packets) {
    }

    static Theora demux(String name) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(resource(name + ".ogv")))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return new Theora(track, packets);
        }
    }

    static List<Packet> sequence(List<Packet> packets, String variant) {
        return switch (variant) {
            case "dup" -> {
                var result = new ArrayList<Packet>();
                for (var index = 0; index < packets.size(); index++) {
                    result.add(packets.get(index));
                    if (index % 3 == 2) {
                        result.add(packets.get(index).withData(new byte[0]));
                    }
                }
                yield result;
            }
            case "nokey" -> packets.subList(1, packets.size());
            default -> packets;
        };
    }

    static byte[] raw(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var row = 0; row < picture.height(); row++) {
            out.write(picture.y(), row * picture.yStride(), picture.width());
        }
        for (var plane : List.of(picture.u(), picture.v())) {
            for (var row = 0; row < picture.chromaHeight(); row++) {
                out.write(plane, row * picture.uvStride(), picture.chromaWidth());
            }
        }
        return out.toByteArray();
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static List<byte[]> decode(TheoraDecoder decoder, List<Packet> packets) {
        var frames = new ArrayList<byte[]>();
        for (var packet : packets) {
            decoder.decode(packet, frame -> frames.add(raw((YuvPicture) frame.picture())));
        }
        return frames;
    }

    static String firstDifference(String name, String format, int frame, byte[] decoded) {
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return "";
        }
        try {
            var file = Files.createTempFile("theora", ".ogv");
            try {
                Files.write(file, resource(name + ".ogv"));
                var reference = Oracle.decodeVideo(file, format, frame + 1);
                var size = decoded.length;
                var start = frame * size;
                for (var i = 0; i < size; i++) {
                    if (start + i < reference.length && reference[start + i] != decoded[i]) {
                        return " (first difference at byte " + i + " of the frame: " + (decoded[i] & 0xFF)
                                + " instead of ffmpeg's " + (reference[start + i] & 0xFF) + ")";
                    }
                }
                return "";
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException exception) {
            return " (ffmpeg comparison failed: " + exception + ")";
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every picture bit-exactly with libtheora")
    void reference(Vector vector) {
        var stream = demux(vector.name());
        var decoder = new TheoraDecoder(stream.track());
        var frames = decode(decoder, sequence(stream.packets(), vector.variant()));
        assertEquals(vector.md5s().size(), frames.size(), vector + ": frame count");
        for (var index = 0; index < frames.size(); index++) {
            var actual = md5(frames.get(index));
            if (!actual.equals(vector.md5s().get(index))) {
                var format = vector.name().startsWith("420") ? "yuv420p" : vector.name().startsWith("422") ? "yuv422p"
                        : "yuv444p";
                var detail = vector.variant().isEmpty() ? firstDifference(vector.name(), format, index, frames.get(index))
                        : "";
                fail(vector + ": frame " + index + " differs" + detail);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("decodes a Matroska remux picture for picture")
    void matroska(String name) {
        var stream = demux(name);
        var sink = MediaSink.memory();
        var muxer = new MatroskaMuxer(sink);
        muxer.addTrack(stream.track());
        stream.packets().forEach(muxer::writePacket);
        muxer.finish();
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(sink.toByteArray()))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.THEORA, track.codec());
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            var expected = decode(new TheoraDecoder(stream.track()), stream.packets());
            var actual = decode(new TheoraDecoder(track), packets);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                assertTrue(Arrays.equals(expected.get(index), actual.get(index)), name + ": frame " + index);
            }
        }
    }

    @Nested
    @DisplayName("stream description")
    class Description {
        @Test
        @DisplayName("is created by the registry and reports the coded frame and the picture formats")
        void registry() {
            var stream = demux("420-odd");
            assertEquals(Codec.THEORA, stream.track().codec());
            assertEquals(90, stream.track().width());
            assertEquals(54, stream.track().height());
            var decoder = VideoDecoders.create(stream.track());
            var theora = assertInstanceOf(TheoraDecoder.class, decoder);
            assertEquals(96, theora.frameWidth());
            assertEquals(64, theora.frameHeight());
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(stream.packets().getFirst(), frames::add);
            var picture = assertInstanceOf(YuvPicture.class, frames.getFirst().picture());
            assertEquals(90, picture.width());
            assertEquals(54, picture.height());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            assertTrue(frames.getFirst().keyframe());
            assertEquals(stream.packets().getFirst().pts(), frames.getFirst().pts());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.theora.TheoraDecoderTest#formats")
        @DisplayName("outputs the pixel format the stream codes")
        void formats(String name, ChromaFormat format) {
            var stream = demux(name);
            var frames = new ArrayList<VideoFrame>();
            new TheoraDecoder(stream.track()).decode(stream.packets().getFirst(), frames::add);
            assertEquals(format, ((YuvPicture) frames.getFirst().picture()).chromaFormat());
        }

        @Test
        @DisplayName("marks only intra frames as key frames and skips header packets")
        void keyframes() {
            var stream = demux("420-q5");
            var decoder = new TheoraDecoder(stream.track());
            var frames = new ArrayList<VideoFrame>();
            var header = stream.packets().getFirst().withData(new byte[]{(byte) 0x81, 't', 'h', 'e', 'o', 'r', 'a'});
            decoder.decode(header, frames::add);
            assertEquals(0, frames.size());
            for (var packet : stream.packets()) {
                decoder.decode(packet, frames::add);
            }
            for (var index = 0; index < frames.size(); index++) {
                assertEquals(stream.packets().get(index).keyframe(), frames.get(index).keyframe(), "frame " + index);
            }
            assertTrue(frames.getFirst().keyframe());
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> formats() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("420-q5", ChromaFormat.YUV420),
                org.junit.jupiter.params.provider.Arguments.of("422-q6", ChromaFormat.YUV422),
                org.junit.jupiter.params.provider.Arguments.of("444-q6", ChromaFormat.YUV444));
    }

    @Nested
    @DisplayName("invalid input")
    class Invalid {
        @Test
        @DisplayName("rejects a track of another codec and a configuration without the headers")
        void configuration() {
            var track = demux("420-q5").track();
            assertThrows(IllegalArgumentException.class,
                    () -> new TheoraDecoder(track.toBuilder().codec(Codec.VP9).build()));
            assertThrows(MediaException.Malformed.class,
                    () -> new TheoraDecoder(track.toBuilder().codecConfig(new byte[]{0, 1, 2}).build()));
            var config = track.codecConfig().orElseThrow();
            var truncated = Arrays.copyOf(config, config.length - 900);
            assertThrows(MediaException.class,
                    () -> new TheoraDecoder(track.toBuilder().codecConfig(truncated).build()));
        }

        @Test
        @DisplayName("rejects a header packet passed as a frame")
        void headerFrame() {
            var decoder = new TheoraDecoder(demux("420-q5").track());
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(new byte[]{(byte) 0x80}, 0, 1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.theora.TheoraDecoderTest#names")
        @DisplayName("decodes damaged and truncated packets without failing other than with MediaException")
        void damaged(String name) {
            var stream = demux(name);
            var random = new Random(name.hashCode());
            for (var round = 0; round < 4; round++) {
                var decoder = new TheoraDecoder(stream.track());
                for (var packet : stream.packets()) {
                    var data = packet.data().clone();
                    if (data.length > 0) {
                        if (round % 2 == 0) {
                            for (var flip = 0; flip < 4; flip++) {
                                var bit = random.nextInt(data.length * 8);
                                if (bit >= 8) {
                                    data[bit >> 3] ^= (byte) (1 << (bit & 7));
                                }
                            }
                        } else {
                            data = Arrays.copyOf(data, 1 + random.nextInt(data.length));
                        }
                    }
                    var damaged = packet.withData(data);
                    try {
                        decoder.decode(damaged, frame -> raw((YuvPicture) frame.picture()));
                    } catch (MediaException expected) {
                        // a damaged packet may be rejected; the next packet decodes
                    }
                }
            }
        }
    }
}
