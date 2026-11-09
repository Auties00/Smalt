package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the streams {@link TheoraEncoder} writes for {@link TheoraClips} clips (key frames, inter frames of every
 * macro block mode, scene cuts and short key frame intervals) as {@code smalt-*.packets} resources, and checks that
 * they decode to the pictures libtheora 1.2.0 decodes, whose MD5s {@code encoder-expected.tsv} records, in
 * {@link TheoraDecoder} and in ffmpeg. The resources and the MD5s are made by
 * {@code tools/testdata/theora-oracle/make-encoder-vectors.py}.
 */
@DisplayName("Theora encoder against libtheora")
class TheoraEncoderReferenceTest {
    private static final Path RESOURCES = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "theora");

    /**
     * One pinned stream.
     *
     * @param name the resource name without {@code smalt-} and {@code .packets}
     * @param width the picture width
     * @param height the picture height
     * @param format the chroma format
     * @param quality the quality index
     * @param interval the key frame interval
     * @param frames the number of frames
     * @param cut the first frame of the second scene of the clip
     * @param pixelFormat the pixel format name of ffmpeg
     */
    record Pinned(String name, int width, int height, ChromaFormat format, int quality, int interval, int frames,
                     int cut, String pixelFormat) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Pinned> streams() {
        return Stream.of(
                new Pinned("420", 128, 80, ChromaFormat.YUV420, 40, 64, 24, 16, "yuv420p"),
                new Pinned("420-odd", 90, 54, ChromaFormat.YUV420, 10, 8, 20, 1000, "yuv420p"),
                new Pinned("422", 80, 48, ChromaFormat.YUV422, 24, 64, 16, 1000, "yuv422p"),
                new Pinned("444", 72, 40, ChromaFormat.YUV444, 63, 64, 16, 10, "yuv444p"));
    }

    /**
     * Encodes a clip into the packet listing {@code smalt-theoradec} reads: every packet, the three headers first, as
     * a 32-bit little-endian length and its bytes.
     */
    static byte[] encode(Pinned stream) {
        var options = TheoraEncoderOptions.builder().quality(stream.quality()).chromaFormat(stream.format())
                .keyframeInterval(stream.interval()).build();
        var encoder = new TheoraEncoder(stream.width(), stream.height(), options);
        var packets = new ArrayList<byte[]>(Arrays.asList(XiphLacing.unlace(encoder.codecConfig().orElseThrow())));
        for (var t = 0; t < stream.frames(); t++) {
            var picture = TheoraClips.frame(stream.width(), stream.height(), stream.format(), t, stream.cut());
            encoder.encode(new VideoFrame(picture, t * 3000L, 3000L, false), packet -> packets.add(packet.data()));
        }
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.length).array());
            out.writeBytes(packet);
        }
        return out.toByteArray();
    }

    static List<byte[]> unpack(byte[] listing) {
        var buffer = ByteBuffer.wrap(listing).order(ByteOrder.LITTLE_ENDIAN);
        var packets = new ArrayList<byte[]>();
        while (buffer.hasRemaining()) {
            var packet = new byte[buffer.getInt()];
            buffer.get(packet);
            packets.add(packet);
        }
        return packets;
    }

    static byte[] resource(String name) {
        return TheoraDecoderTest.resource(name);
    }

    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> expected(String name) {
        for (var line : new String(resource("encoder-expected.tsv"), StandardCharsets.UTF_8).split("\n")) {
            var fields = line.strip().split("\t");
            if (fields.length == 2 && fields[0].equals(name)) {
                return List.of(fields[1].split(" "));
            }
        }
        throw new AssertionError("no libtheora MD5s for " + name + " in encoder-expected.tsv");
    }

    private static VideoTrack track(Pinned stream, byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.THEORA).timeBase(new Rational(1, 90_000))
                .width(stream.width()).height(stream.height()).frameRate(new Rational(30, 1)).codecConfig(config)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("writes the pinned stream")
    void pinned(Pinned stream) throws IOException {
        var actual = encode(stream);
        var file = "smalt-" + stream.name() + ".packets";
        if (Dumps.updating()) {
            Files.createDirectories(RESOURCES);
            Files.write(RESOURCES.resolve(file), actual);
            return;
        }
        assertTrue(Arrays.equals(resource(file), actual), file + " differs from the encoder's output; if the "
                + "change is intended, rerun with -Dsmalt.test.updateDumps=true and "
                + "tools/testdata/theora-oracle/make-encoder-vectors.py");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("decodes the pinned stream to libtheora's pictures, in Smalt and in ffmpeg")
    void reference(Pinned stream) throws IOException {
        var packets = unpack(resource("smalt-" + stream.name() + ".packets"));
        var config = XiphLacing.lace(packets.subList(0, 3).toArray(byte[][]::new));
        var data = packets.subList(3, packets.size());
        var md5s = expected(stream.name());
        assertEquals(data.size(), md5s.size(), "frames");
        var track = track(stream, config);
        var decoder = new TheoraDecoder(track);
        var ours = new ByteArrayOutputStream();
        var keyframes = 0;
        var interframes = 0;
        for (var index = 0; index < data.size(); index++) {
            var packet = data.get(index);
            var keyframe = packet.length > 0 && (packet[0] & 0x40) == 0;
            if (keyframe) {
                keyframes++;
            } else {
                interframes++;
            }
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(new Packet(1, packet, index * 3000L, index * 3000L, 3000L, keyframe), frames::add);
            var decoded = TheoraEncoderTest.raw((YuvPicture) frames.getFirst().picture());
            assertEquals(md5s.get(index), md5(decoded), "frame " + index + " against libtheora");
            ours.writeBytes(decoded);
        }
        assertTrue(keyframes >= 1 && interframes > keyframes, keyframes + " key frames, " + interframes
                + " inter frames");
        var sink = MediaSink.memory();
        var muxer = new OggMuxer(sink);
        muxer.addTrack(track);
        for (var index = 0; index < data.size(); index++) {
            var packet = data.get(index);
            muxer.writePacket(new Packet(1, packet, index * 3000L, index * 3000L, 3000L,
                    packet.length > 0 && (packet[0] & 0x40) == 0));
        }
        muxer.finish();
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Scratch.directory("theora-reference").resolve(stream.name() + ".ogv");
        Files.write(file, sink.toByteArray());
        assertArrayEquals(ours.toByteArray(), Oracle.decodeVideo(file, stream.pixelFormat(), data.size()),
                "ffmpeg's decode");
    }
}
