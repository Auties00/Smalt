package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes test pictures with every ProRes flavor, muxes them into QuickTime files and checks them
 * with ffmpeg as an independent decoder: the files must decode without a single error, carry the
 * flavor's sample entry, and reach a per-flavor PSNR against the source; Smalt's own decoder
 * must agree with ffmpeg's decode of the same files within the IDCT accuracy bounds.
 */
@DisplayName("ProRes encoder")
class ProResEncoderTest {
    static final Rational FRAME_RATE = new Rational(25, 1);

    static Path mux(List<Packet> packets, ProResEncoder encoder, Path file) {
        try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.PRORES).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(encoder.width()).height(encoder.height())
                    .frameRate(FRAME_RATE).build());
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(1));
            }
            muxer.finish();
        }
        return file;
    }

    static List<Packet> encode(List<Picture> pictures, ProResEncoder encoder) {
        var packets = new ArrayList<Packet>();
        var pts = 0L;
        for (var picture : pictures) {
            encoder.encode(new VideoFrame(picture, pts, 3600, true), packets::add);
            pts += 3600;
        }
        return packets;
    }

    static byte[] raw(List<Picture> pictures, boolean alpha) {
        var out = new ByteArrayOutputStream();
        pictures.forEach(picture -> out.writeBytes(IntraTesting.raw(picture, alpha)));
        return out.toByteArray();
    }

    static Stream<Arguments> flavors() {
        return Stream.of(
                Arguments.of(ProResProfile.PROXY, 30.0),
                Arguments.of(ProResProfile.LT, 36.0),
                Arguments.of(ProResProfile.STANDARD, 39.0),
                Arguments.of(ProResProfile.HQ, 42.0),
                Arguments.of(ProResProfile.PRORES_4444, 42.0),
                Arguments.of(ProResProfile.PRORES_4444_XQ, 44.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flavors")
    @DisplayName("produces files ffmpeg decodes cleanly at the flavor's quality")
    void flavor(ProResProfile profile, double minimumPsnr, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var alpha = profile.supportsAlpha();
        var format = alpha ? "yuva444p12le" : "yuv422p10le";
        var width = 352;
        var height = 288;
        var source = IntraTesting.sourceFrames(width, height, format, 3);
        var encoder = new ProResEncoder(width, height, ProResEncoderOptions.of(profile).toBuilder()
                .frameRate(FRAME_RATE).build());
        var packets = encode(source, encoder);
        var file = mux(packets, encoder, directory.resolve(profile + ".mov"));
        Oracle.assertDecodesCleanly(file);
        var probe = Oracle.probe(file);
        assertTrue(probe.contains("\"codec_tag_string\": \"" + profile.fourCc() + "\""), probe);
        var decoded = IntraTesting.ffmpegDecode(file, format, 0);
        var expected = raw(source, alpha);
        var psnr = IntraTesting.psnr(expected, decoded, true, (1 << profile.bitDepth()) - 1);
        var bytes = packets.stream().mapToLong(Packet::size).sum();
        var bitrate = bytes * 8 * FRAME_RATE.toDouble() / packets.size();
        System.out.printf("%s: psnr=%.2f dB, %.1f Mb/s, target %.1f Mb/s%n", profile, psnr, bitrate / 1e6,
                profile.bitsPerMacroblock() * 22 * 18 * FRAME_RATE.toDouble() / 1e6);
        assertTrue(psnr >= minimumPsnr, profile + " PSNR " + psnr);
        if (alpha) {
            var alphaOffset = 3 * width * height * 2;
            var frameSize = 4 * width * height * 2;
            for (var f = 0; f < source.size(); f++) {
                var from = f * frameSize + alphaOffset;
                assertArrayEquals(java.util.Arrays.copyOfRange(expected, from, from + width * height * 2),
                        java.util.Arrays.copyOfRange(decoded, from, from + width * height * 2), "alpha is lossless");
            }
        }
        var decoder = new ProResDecoder();
        var ours = new ByteArrayOutputStream();
        for (var packet : packets) {
            decoder.decode(packet, frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), alpha)));
        }
        assertTrue(IntraTesting.maxDifference(decoded, ours.toByteArray(), true) <= 1, "Smalt and ffmpeg decode alike");
    }

    @Nested
    @DisplayName("rate control")
    class Rate {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ProResProfile.class)
        @DisplayName("stays below the flavor's target rate")
        void target(ProResProfile profile) {
            var width = 640;
            var height = 360;
            var source = IntraTesting.sourceFrames(width, height, profile.supportsAlpha() ? "yuv444p12le" : "yuv422p10le", 2);
            var encoder = new ProResEncoder(width, height, ProResEncoderOptions.of(profile).toBuilder()
                    .frameRate(FRAME_RATE).build());
            var packets = encode(source, encoder);
            var macroblocks = 40 * 23;
            var budget = profile.bitsPerMacroblock() * macroblocks / 8;
            for (var packet : packets) {
                assertTrue(packet.size() <= budget * 1.05 + 4096, profile + " frame of " + packet.size() + " bytes, budget " + budget);
            }
        }

        @Test
        @DisplayName("honors a fixed quantization index")
        void fixedIndex() {
            var source = IntraTesting.sourceFrames(64, 48, "yuv422p10le", 1);
            var fine = new ProResEncoder(64, 48, ProResEncoderOptions.builder().quantizationIndex(1).build());
            var coarse = new ProResEncoder(64, 48, ProResEncoderOptions.builder().quantizationIndex(200).build());
            var fineSize = encode(source, fine).getFirst().size();
            var coarseSize = encode(source, coarse).getFirst().size();
            assertTrue(fineSize > coarseSize * 3, fineSize + " vs " + coarseSize);
        }
    }

    @Nested
    @DisplayName("input")
    class Input {
        @Test
        @DisplayName("interlaced frames decode back in field order")
        void interlaced(@TempDir Path directory) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            for (var order : List.of(FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST)) {
                var source = IntraTesting.sourceFrames(176, 101, "yuv422p10le", 2);
                var encoder = new ProResEncoder(176, 101, ProResEncoderOptions.builder().fieldOrder(order)
                        .frameRate(FRAME_RATE).build());
                var file = mux(encode(source, encoder), encoder, directory.resolve(order + ".mov"));
                Oracle.assertDecodesCleanly(file);
                var probe = Oracle.probe(file);
                assertTrue(probe.contains("\"field_order\": \"" + (order == FieldOrder.TOP_FIRST ? "tb" : "bt") + "\""), probe);
                var psnr = IntraTesting.psnr(raw(source, false), IntraTesting.ffmpegDecode(file, "yuv422p10le", 0), true, 1023);
                assertTrue(psnr > 40, order + " PSNR " + psnr);
            }
        }

        @Test
        @DisplayName("accepts 8-bit Y'CbCr and RGBA pictures")
        void otherRepresentations() {
            var yuv = IntraTesting.sourceFrames(96, 64, "yuv422p", 1);
            var rgba = IntraTesting.sourceFrames(96, 64, "rgba", 1);
            var encoder = new ProResEncoder(96, 64, ProResEncoderOptions.of(ProResProfile.PRORES_4444));
            var decoder = new ProResDecoder();
            for (var picture : List.of(yuv.getFirst(), rgba.getFirst())) {
                var packet = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true));
                decoder.decode(packet, frame -> assertEquals(picture.hasAlpha(), frame.picture().hasAlpha()));
            }
        }

        @Test
        @DisplayName("rejects pictures of another size")
        void wrongSize() {
            var encoder = new ProResEncoder(64, 48);
            var picture = IntraTesting.sourceFrames(32, 32, "yuv422p10le", 1).getFirst();
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(picture, 0, 0, true)));
        }
    }
}
