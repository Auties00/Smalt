package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with the H.263 encoder in the baseline syntax and with the optional modes it codes, and
 * checks the encoder's reconstruction against Smalt's decode of the stream (sample for sample), the stream against
 * ffmpeg's decode, the container round trips, the rate control and the options.
 */
@DisplayName("H.263 encoder")
class H263EncoderTest {
    static final Rational TIME_BASE = new Rational(1, 30);

    record Case(String name, int width, int height, int frames, H263EncoderOptions options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static H263EncoderOptions.Builder base() {
        return H263EncoderOptions.builder().timeBase(TIME_BASE).frameRate(new Rational(30, 1)).bitrate(300_000);
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("baseline QCIF", 176, 144, 12, base().build()),
                new Case("baseline sub-QCIF", 128, 96, 8, base().build()),
                new Case("baseline CIF with GOB headers", 352, 288, 6, base().groupOfBlocksHeaders(true).build()),
                new Case("finest quantizer", 128, 96, 4, base().fixedQuantizer(1).build()),
                new Case("coarsest quantizer", 128, 96, 4, base().fixedQuantizer(31).build()),
                new Case("custom format", 200, 120, 8, base().build()),
                new Case("custom format, partial macroblocks", 100, 60, 6, base().build()),
                new Case("Annex D", 176, 144, 8, base().unrestrictedVectors(true).build()),
                new Case("Annex F", 176, 144, 8, base().advancedPrediction(true).build()),
                new Case("Annexes D and F", 176, 144, 8, base().unrestrictedVectors(true).advancedPrediction(true)
                        .build()),
                new Case("Annex I", 176, 144, 8, base().advancedIntraCoding(true).build()),
                new Case("Annex J", 176, 144, 8, base().deblockingFilter(true).build()),
                new Case("Annex T", 176, 144, 8, base().modifiedQuantization(true).build()),
                new Case("Annex T at a small quantizer", 176, 144, 4, base().modifiedQuantization(true)
                        .fixedQuantizer(2).build()),
                new Case("profile 1", 176, 144, 8, H263EncoderOptions.forCodingEfficiency().toBuilder()
                        .timeBase(TIME_BASE).frameRate(new Rational(30, 1)).build()),
                new Case("every mode with GOB headers", 176, 144, 8, base().unrestrictedVectors(true)
                        .advancedPrediction(true).advancedIntraCoding(true).deblockingFilter(true)
                        .modifiedQuantization(true).groupOfBlocksHeaders(true).build()),
                new Case("Annex K", 176, 144, 8, base().sliceSize(11).build()),
                new Case("Annex K with odd slices and Annex F", 176, 144, 8, base().sliceSize(17)
                        .advancedPrediction(true).build()),
                new Case("profile 3", 352, 288, 6, base().advancedIntraCoding(true).deblockingFilter(true)
                        .sliceSize(44).modifiedQuantization(true).build()),
                new Case("custom clock", 176, 144, 6, base().frameRate(new Rational(25, 1)).timeBase(new Rational(1, 25))
                        .deblockingFilter(true).build())
        );
    }

    static EncoderScenes.Encoded encode(Case c) {
        try (var encoder = new H263Encoder(c.width(), c.height(), c.options())) {
            return EncoderScenes.encode(encoder, c.frames(), 1, H263Encoder::lastReconstruction);
        }
    }

    static VideoTrack track(Case c) {
        return VideoTrack.builder().id(1).codec(Codec.H263).timeBase(c.options().timeBase()).width(c.width())
                .height(c.height()).build();
    }

    static Path write(String name, byte[] data) {
        try {
            var file = Scratch.directory("h263-encoder").resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("reconstructs every picture exactly as Smalt's decoder decodes the stream")
    void reconstruction(Case c) {
        var encoded = encode(c);
        assertEquals(c.frames(), encoded.packets().size(), "one packet per picture");
        var decoded = EncoderScenes.decode(track(c), encoded.packets(), H263Decoder::new);
        assertEquals(c.frames(), decoded.size(), "decoded pictures");
        for (var i = 0; i < decoded.size(); i++) {
            assertEquals(i, decoded.get(i).pts());
            assertArrayEquals(encoded.reconstructions().get((long) i), decoded.get(i).samples(), c + ": picture " + i);
        }
        for (var packet : encoded.packets()) {
            var data = packet.data();
            assertEquals(0, data[0]);
            assertEquals(0, data[1]);
            assertEquals(0x80, data[2] & 0xFC, "picture start code");
            assertEquals(packet.pts(), packet.dts());
        }
        assertTrue(encoded.packets().getFirst().keyframe());
    }

    @Test
    @DisplayName("codes the standard formats without the optional modes in the baseline syntax")
    void baselineSyntax() {
        try (var encoder = new H263Encoder(176, 144, base().advancedPrediction(true).build())) {
            assertFalse(encoder.extendedType());
            var packets = new ArrayList<Packet>();
            encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, 0), 0, 1, false), packets::add);
            // PTYPE bits 6-8 (source format 2, QCIF), after the 22-bit PSC and the 8-bit TR.
            var data = packets.getFirst().data();
            var ptype = ((data[3] & 0x03) << 11) | ((data[4] & 0xFF) << 3) | ((data[5] & 0xFF) >> 5);
            assertEquals(0b10, ptype >> 11, "PTYPE starts with 1 0");
            assertEquals(2, (ptype >> 5) & 7, "source format QCIF");
            assertEquals(1, (ptype >> 1) & 1, "Annex F signaled in PTYPE");
        }
        try (var encoder = new H263Encoder(176, 144, base().deblockingFilter(true).build())) {
            assertTrue(encoder.extendedType());
        }
        try (var encoder = new H263Encoder(200, 120, base().build())) {
            assertTrue(encoder.extendedType());
        }
    }

    @Test
    @DisplayName("chooses the slowest custom picture clock the frame rate divides")
    void customClock() {
        assertArrayEquals(new int[]{0, 0}, H263Encoder.customClock(new Rational(30000, 1001)));
        assertArrayEquals(new int[]{0, 0}, H263Encoder.customClock(new Rational(15000, 1001)));
        assertArrayEquals(new int[]{1000, 72}, H263Encoder.customClock(new Rational(25, 1)));
        assertArrayEquals(new int[]{1000, 60}, H263Encoder.customClock(new Rational(30, 1)));
        assertArrayEquals(new int[]{1001, 75}, H263Encoder.customClock(new Rational(24000, 1001)));
        assertArrayEquals(new int[]{1000, 90}, H263Encoder.customClock(new Rational(10, 1)));
    }

    @Test
    @DisplayName("the reconstruction tracks the source: PSNR rises as the quantizer falls")
    void quality() {
        var source = EncoderScenes.source(176, 144, 6);
        var previous = 0.0;
        for (var quantizer : new int[]{24, 12, 6, 3}) {
            var c = new Case("q" + quantizer, 176, 144, 6, base().fixedQuantizer(quantizer).build());
            var encoded = encode(c);
            var decoded = EncoderScenes.decode(track(c), encoded.packets(), H263Decoder::new);
            var psnr = Tolerance.Difference.of(source, EncoderScenes.concat(decoded.stream()
                    .map(EncoderScenes.Decoded::samples).toList())).psnr();
            assertTrue(psnr > previous + 1, "quantizer " + quantizer + " gives " + psnr + " dB after " + previous);
            previous = psnr;
        }
        assertTrue(previous > 36, "quantizer 3 gives " + previous + " dB");
    }

    @Test
    @DisplayName("holds the target bitrate within 15 percent")
    void bitrate() {
        for (var target : new long[]{96_000, 256_000}) {
            var c = new Case("rate", 176, 144, 90, base().bitrate(target).keyframeInterval(30).build());
            var bits = 0L;
            for (var packet : encode(c).packets()) {
                bits += packet.data().length * 8L;
            }
            var rate = bits * 30.0 / c.frames();
            assertTrue(Math.abs(rate - target) < 0.15 * target, "target " + target + " coded at " + rate);
        }
    }

    @Test
    @DisplayName("codes the same stream on one thread and on four")
    void threads() {
        var one = encode(new Case("one", 352, 288, 5, base().advancedPrediction(true).threads(1).build()));
        var four = encode(new Case("four", 352, 288, 5, base().advancedPrediction(true).threads(4).build()));
        assertArrayEquals(one.elementary(), four.elementary());
    }

    @Test
    @DisplayName("MPEG-4 Part 2 decoders read the baseline stream as the short video header")
    void shortVideoHeader() {
        var c = new Case("short header", 176, 144, 6, base().build());
        var encoded = encode(c);
        var track = VideoTrack.builder().id(1).codec(Codec.MPEG4_PART2).timeBase(TIME_BASE).width(176).height(144)
                .build();
        var decoded = EncoderScenes.decode(track, encoded.packets(), Mpeg4Decoder::new);
        assertEquals(6, decoded.size());
        for (var frame : decoded) {
            assertArrayEquals(encoded.reconstructions().get(frame.pts()), frame.samples());
        }
    }

    @Test
    @DisplayName("is found through the codec registry and rejects sizes H.263 cannot code")
    void registry() {
        var options = VideoEncoderOptions.of(176, 144, new Rational(15, 1), 128_000);
        try (var encoder = VideoEncoders.create(Codec.H263, options)) {
            assertEquals(Codec.H263, encoder.codec());
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 4; i++) {
                encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, i), i, 1, false), packets::add);
            }
            assertEquals(4, packets.size());
            assertFalse(((H263Encoder) encoder).extendedType());
        }
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.H263,
                VideoEncoderOptions.of(174, 144, new Rational(15, 1), 128_000)));
    }

    @Test
    @DisplayName("rejects invalid options and sizes")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> new H263Encoder(2052, 144));
        assertThrows(IllegalArgumentException.class, () -> new H263Encoder(176, 1156));
        assertThrows(IllegalArgumentException.class, () -> new H263Encoder(177, 144));
        assertThrows(IllegalArgumentException.class, () -> base().fixedQuantizer(32).build());
        assertThrows(IllegalArgumentException.class, () -> base().keyframeInterval(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().searchRange(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().sliceSize(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().sliceSize(11).groupOfBlocksHeaders(true).build());
        assertTrue(H263Encoder.codesSize(1408, 1152));
        assertTrue(H263Encoder.codesSize(4, 4));
        assertFalse(H263Encoder.codesSize(2, 4));
        var options = base().deblockingFilter(true).build();
        assertEquals(options, options.toBuilder().build());
        try (var encoder = new H263Encoder(128, 96, base().fixedQuantizer(5).build())) {
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(64_000));
        }
    }

    @Test
    @DisplayName("codes an I-picture at a key frame request")
    void keyframeRequest() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H263Encoder(128, 96, base().noPeriodicKeyframes().build())) {
            for (var i = 0; i < 6; i++) {
                if (i == 4) {
                    encoder.requestKeyframe();
                }
                encoder.encode(new VideoFrame(EncoderScenes.picture(128, 96, i), i, 1, false), packets::add);
            }
        }
        assertEquals(List.of(0L, 4L), packets.stream().filter(Packet::keyframe).map(Packet::pts).toList());
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static Stream<Case> oracleCases() {
            return cases();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("oracleCases")
        @DisplayName("ffmpeg decodes the stream within the inverse DCT noise floor of Smalt's decode")
        void decodes(Case c) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var encoded = encode(c);
            var file = write(c.name().replaceAll("[^A-Za-z0-9]+", "-") + ".263", encoded.elementary());
            var integer = ffmpeg(file, "int");
            var decoded = EncoderScenes.concat(EncoderScenes.decode(track(c), encoded.packets(), H263Decoder::new)
                    .stream().map(EncoderScenes.Decoded::samples).toList());
            assertEquals(decoded.length, integer.length, "ffmpeg's decoded size");
            // ffmpeg takes some remote vectors of overlapped motion compensation differently (see the README).
            var floor = c.options().advancedPrediction() ? 30 : 45;
            Tolerance.psnr(floor).assertWithin(c + " against ffmpeg -idct int", integer, decoded);
        }

        static byte[] ffmpeg(Path file, String idct) {
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-cpuflags", "0",
                    "-idct", idct, "-f", "h263", "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
        }
    }

    @Nested
    @DisplayName("in containers")
    class InContainers {
        static Stream<String> containers() {
            return Stream.of("3gp", "mp4", "avi", "mkv", "263");
        }

        static Muxer muxer(String container, MediaSink sink) {
            return switch (container) {
                case "3gp" -> new Mp4Muxer(sink, Mp4MuxerOptions.threeGpp());
                case "mp4" -> new Mp4Muxer(sink);
                case "avi" -> new AviMuxer(sink);
                case "mkv" -> new MatroskaMuxer(sink);
                default -> Containers.openMuxer(ContainerFormat.H263, sink);
            };
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("containers")
        @DisplayName("muxes the stream, demuxes the same packets and decodes the same pictures")
        void roundTrip(String container) {
            var c = new Case("container", 176, 144, 8, base().build());
            var encoded = encode(c);
            var track = track(c);
            var sink = MediaSink.memory();
            try (var muxer = muxer(container, sink)) {
                muxer.addTrack(track);
                for (var packet : encoded.packets()) {
                    muxer.writePacket(new Packet(track.id(), packet.data(), packet.pts(), packet.dts(),
                            packet.duration(), packet.flags(), packet.sideData()));
                }
                muxer.finish();
            }
            var bytes = sink.toByteArray();
            var demuxed = new ArrayList<Packet>();
            VideoTrack read;
            try (var source = MediaSource.of(bytes); var demuxer = Containers.openDemuxer(source)) {
                read = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.H263, read.codec());
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    demuxed.add(packet);
                }
            }
            assertEquals(encoded.packets().size(), demuxed.size());
            for (var i = 0; i < demuxed.size(); i++) {
                assertArrayEquals(encoded.packets().get(i).data(), demuxed.get(i).data(), container + " packet " + i);
            }
            var decoded = EncoderScenes.decode(read, demuxed, H263Decoder::new);
            assertEquals(c.frames(), decoded.size());
            for (var i = 0; i < decoded.size(); i++) {
                assertArrayEquals(encoded.reconstructions().get((long) i), decoded.get(i).samples(),
                        container + " picture " + i);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = write("round-trip." + container, bytes);
                Oracle.assertDecodesCleanly(file);
                if (container.equals("3gp")) {
                    var probe = Oracle.probe(file);
                    assertTrue(probe.contains("\"major_brand\": \"3gp6\""), probe);
                }
            }
        }
    }
}
