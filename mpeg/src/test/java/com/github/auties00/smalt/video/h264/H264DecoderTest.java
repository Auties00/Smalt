package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public surface of {@link H264Decoder} and {@link H264StreamInfo}: construction
 * checks, lifecycle, thumbnail decoding, keyframe flags, color description, cropping, limits
 * and the documented rejection of unsupported coding tools.
 */
@DisplayName("H264Decoder")
class H264DecoderTest {
    private static final VideoTrack ANNEX_B = H264TestStreams.track(Optional.empty(), Optional.empty());

    private static Path encode(String size, int frames, String pixelFormat, List<String> options) {
        var output = new ArrayList<String>(List.of("-vf", "format=" + pixelFormat, "-frames:v", Integer.toString(frames),
                "-c:v", "libx264"));
        output.addAll(options);
        output.addAll(List.of("-f", "h264"));
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=25"), output);
    }

    private static Path standard() {
        return encode("176x144", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "3", "-x264-params", "keyint=12:open-gop=1"));
    }

    private static List<Packet> accessUnits(Path stream) {
        var payloads = new ArrayList<byte[]>();
        for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(stream)))) {
            payloads.add(H264TestStreams.annexB(unit));
        }
        return H264TestStreams.packets(payloads);
    }

    @Nested
    @DisplayName("construction and lifecycle")
    class Lifecycle {
        @Test
        @DisplayName("rejects tracks of another codec and non-positive thread counts")
        void arguments() {
            var vp8 = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 1000)).width(16).height(16)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new H264Decoder(vp8));
            assertThrows(IllegalArgumentException.class, () -> new H264Decoder(ANNEX_B, H264DecoderOptions.builder().threads(-1).build()));
            assertThrows(NullPointerException.class, () -> new H264Decoder(null));
        }

        @Test
        @DisplayName("rejects a corrupt avcC record at construction")
        void corruptRecord() {
            var record = new byte[]{1, 100, 0, 30, (byte) 0xFF, (byte) 0xE1, 0, 40, 0x67};
            assertThrows(MediaException.Malformed.class,
                    () -> new H264Decoder(H264TestStreams.track(Optional.of(record), Optional.empty())));
        }

        @Test
        @DisplayName("fails calls after close, and close is idempotent")
        void closed() {
            var decoder = new H264Decoder(ANNEX_B, H264DecoderOptions.builder().threads(2).build());
            decoder.close();
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.decode(new Packet(1, new byte[0], 0, 0, 0, false), frame -> {
            }));
            assertThrows(IllegalStateException.class, () -> decoder.flush(frame -> {
            }));
        }

        @Test
        @DisplayName("an empty packet yields nothing")
        void emptyPacket() {
            var frames = H264TestStreams.decode(ANNEX_B, List.of(new Packet(1, new byte[0], 0, Packet.NO_TIMESTAMP, 0, false)), 1);
            assertTrue(frames.isEmpty());
        }

        @Test
        @DisplayName("frame threads start no threads of their own, and closing mid-stream leaves no helper running decoder work")
        void closeMidStream() {
            var packets = accessUnits(standard());
            for (var cut : new int[]{1, 5, packets.size() / 2, packets.size()}) {
                try (var decoder = new H264Decoder(ANNEX_B, H264DecoderOptions.builder().threads(4).build())) {
                    for (var packet : packets.subList(0, cut)) {
                        decoder.decode(packet, frame -> {
                        });
                    }
                }
                for (var entry : Thread.getAllStackTraces().entrySet()) {
                    var name = entry.getKey().getName();
                    assertFalse(name.startsWith("h264-decoder"), name);
                    if (name.startsWith("smalt-worker-")) {
                        for (var frame : entry.getValue()) {
                            assertFalse(frame.getClassName().contains(".video.h264."), name + " still runs " + frame);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("many frame-threaded decoders on virtual threads at once produce the frames of one thread, on the bounded pool")
        void concurrentDecoders() throws Exception {
            var packets = accessUnits(standard());
            var single = H264TestStreams.decode(ANNEX_B, packets, 1);
            var peak = 0;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<List<H264TestStreams.Decoded>>>();
                for (var i = 0; i < 16; i++) {
                    var threads = 2 + i % 7;
                    futures.add(executor.submit(() -> H264TestStreams.decode(ANNEX_B, packets, threads)));
                }
                while (!futures.stream().allMatch(Future::isDone)) {
                    peak = Math.max(peak, (int) Thread.getAllStackTraces().keySet().stream()
                            .filter(thread -> thread.getName().startsWith("smalt-worker-") && thread.isAlive())
                            .count());
                    Thread.sleep(2);
                }
                for (var future : futures) {
                    var frames = future.get();
                    assertEquals(single.size(), frames.size());
                    for (var i = 0; i < single.size(); i++) {
                        assertArrayEquals(single.get(i).samples(), frames.get(i).samples(), "frame " + i);
                    }
                }
            }
            // pictures blocked on their references let one more helper run each, up to THREADS - 1 more
            assertTrue(peak <= 2 * ParallelBands.THREADS - 1, peak + " helpers alive at once");
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {
        @Test
        @DisplayName("the first access unit alone decodes to one keyframe thumbnail")
        void thumbnail() {
            var stream = standard();
            var first = accessUnits(stream).getFirst();
            var frames = H264TestStreams.decode(ANNEX_B, List.of(first), 1);
            assertEquals(1, frames.size());
            assertTrue(frames.getFirst().keyframe());
            var expected = Oracle.decodeVideo(stream, "yuv420p", 1);
            assertArrayEquals(expected, frames.getFirst().samples());
        }

        @Test
        @DisplayName("keyframes are the IDR pictures and the I pictures with a recovery point")
        void keyframes() {
            var stream = standard();
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(stream), 1);
            var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-select_streams", "v:0", "-show_entries", "frame=key_frame", "-of", "csv=p=0", stream.toString())),
                    StandardCharsets.US_ASCII).strip().split("\\R");
            assertEquals(probe.length, frames.size());
            var count = 0;
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(probe[i].strip().startsWith("1"), frames.get(i).keyframe(), "frame " + i);
                count += frames.get(i).keyframe() ? 1 : 0;
            }
            assertTrue(count >= 3, "open GOP stream has recovery-point keyframes");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"18x18", "30x14", "176x144", "1920x1080"})
        @DisplayName("output is cropped to the display size")
        void cropping(String size) {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode(size, 2, "yuv420p", List.of())), 1);
            var dimensions = size.split("x");
            assertEquals(Integer.parseInt(dimensions[0]), frames.getFirst().width());
            assertEquals(Integer.parseInt(dimensions[1]), frames.getFirst().height());
        }

        @Test
        @DisplayName("a 4:0:0 stream decodes to monochrome pictures")
        void monochrome() {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode("64x48", 3, "gray", List.of())), 1);
            assertEquals(ChromaFormat.MONOCHROME, frames.getFirst().format());
            assertEquals(64 * 48, frames.getFirst().samples().length);
        }

        @Test
        @DisplayName("pts are derived from the VUI timing when neither packets nor track have them")
        void vuiTiming() {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(standard()), 1);
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(i * 3600L, frames.get(i).pts(), "frame " + i);
                assertEquals(3600, frames.get(i).duration());
            }
        }

        @Test
        @DisplayName("packet pts beyond the track duration are passed through")
        void largeTimestamps() {
            var packets = accessUnits(encode("64x64", 5, "yuv420p", List.of("-bf", "0")));
            var stamped = new ArrayList<Packet>();
            for (var i = 0; i < packets.size(); i++) {
                stamped.add(new Packet(1, packets.get(i).data(), 1_000_000_000L + i * 1000L, Packet.NO_TIMESTAMP, 1000, i == 0));
            }
            var frames = H264TestStreams.decode(ANNEX_B, stamped, 1);
            assertEquals(5, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(1_000_000_000L + i * 1000L, frames.get(i).pts());
                assertEquals(1000, frames.get(i).duration());
            }
        }

        @Test
        @DisplayName("NAL units split across several packets of one picture decode the same")
        void nalPerPacket() {
            var stream = standard();
            var whole = H264TestStreams.decode(ANNEX_B, accessUnits(stream), 1);
            var perNal = new ArrayList<byte[]>();
            for (var nal : H264TestStreams.nalUnits(Fixtures.read(stream))) {
                perNal.add(H264TestStreams.annexB(List.of(nal)));
            }
            var split = H264TestStreams.decode(ANNEX_B, H264TestStreams.packets(perNal), 1);
            assertEquals(whole.size(), split.size());
            for (var i = 0; i < whole.size(); i++) {
                assertArrayEquals(whole.get(i).samples(), split.get(i).samples());
            }
        }
    }

    @Test
    @DisplayName("the consumer's exception propagates unchanged from flush")
    void flushConsumerFailure() {
        var failure = new IllegalStateException("consumer");
        try (var decoder = new H264Decoder(ANNEX_B)) {
            for (var packet : accessUnits(standard())) {
                decoder.decode(packet, frame -> {
                });
            }
            var thrown = assertThrows(RuntimeException.class, () -> decoder.flush(frame -> {
                throw failure;
            }));
            assertSame(failure, thrown);
        }
    }

    @Nested
    @DisplayName("color description")
    class Color {
        @Test
        @DisplayName("defaults to BT.601 limited for standard definition")
        void standardDefinition() {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode("720x576", 1, "yuv420p", List.of())), 1);
            assertEquals(ColorInfo.BT601_LIMITED, frames.getFirst().color());
        }

        @Test
        @DisplayName("defaults to BT.709 limited for high definition")
        void highDefinition() {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode("1280x720", 1, "yuv420p", List.of())), 1);
            assertEquals(ColorInfo.BT709_LIMITED, frames.getFirst().color());
        }

        @Test
        @DisplayName("follows the VUI color description and full range flag")
        void signaled() {
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode("64x64", 1, "yuv420p", List.of("-color_range", "pc",
                    "-x264-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709"))), 1);
            var color = frames.getFirst().color();
            assertEquals(ColorRange.FULL, color.range());
            assertEquals(MatrixCoefficients.BT709, color.matrix());
            assertEquals(ColorPrimaries.BT709, color.primaries());
            assertEquals(ChromaSiting.UNSPECIFIED, color.chromaSiting());
            assertTrue(color.masteringDisplay().isEmpty());
        }

        @Test
        @DisplayName("follows the VUI chroma location and the HDR SEI messages")
        void hdr() {
            // x264 writes mastering-display in the SEI's own units: 0.00002 for chromaticities, 0.0001 cd/m2
            var frames = H264TestStreams.decode(ANNEX_B, accessUnits(encode("64x64", 2, "yuv420p", List.of(
                    "-x264-params", "chromaloc=2:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                            + ":mastering-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,50)"
                            + ":cll=1000,400"))), 1);
            for (var frame : frames) {
                var color = frame.color();
                assertEquals(ChromaSiting.TOP_LEFT, color.chromaSiting());
                assertEquals(new ContentLightLevel(1000, 400), color.contentLightLevel().orElseThrow());
                var display = color.masteringDisplay().orElseThrow();
                assertEquals(0.708, display.red().x(), 1e-9);
                assertEquals(0.797, display.green().y(), 1e-9);
                assertEquals(0.3127, display.whitePoint().x(), 1e-9);
                assertEquals(1000, display.maxLuminance(), 1e-9);
                assertEquals(0.005, display.minLuminance(), 1e-9);
            }
        }
    }

    @Nested
    @DisplayName("stream probing")
    class Probing {
        @Test
        @DisplayName("probes an Annex B stream, an avcC record and a bare SPS alike")
        void probe() {
            var bytes = Fixtures.read(encode("1920x1080", 1, "yuv420p", List.of("-profile:v", "high")));
            var nals = H264TestStreams.nalUnits(bytes);
            var sps = nals.stream().filter(nal -> (nal[0] & 0x1F) == 7).findFirst().orElseThrow();
            for (var input : List.of(bytes, H264TestStreams.avcC(nals, 4, true), sps)) {
                var info = H264StreamInfo.probe(input);
                assertEquals(100, info.profileIdc());
                assertEquals(1920, info.codedWidth());
                assertEquals(1088, info.codedHeight());
                assertEquals(1920, info.width());
                assertEquals(1080, info.height());
                assertEquals(8, info.bitDepthLuma());
                assertTrue(info.frameMbsOnly());
                assertTrue(info.decoderSupported());
                assertEquals(Optional.of(new Rational(25, 1)), info.frameRate());
            }
            assertEquals(OptionalInt.of(4), H264StreamInfo.probe(H264TestStreams.avcC(nals, 4, true)).nalLengthSize());
            assertEquals(OptionalInt.empty(), H264StreamInfo.probe(bytes).nalLengthSize());
        }

        @Test
        @DisplayName("fails on input without a sequence parameter set")
        void noSps() {
            assertThrows(MediaException.Malformed.class, () -> H264StreamInfo.probe(new byte[]{0, 0, 1, 0x68, 1, 2}));
            assertThrows(MediaException.Malformed.class, () -> H264StreamInfo.probe(new byte[0]));
        }

        @Test
        @DisplayName("reports 4:4:4 streams as unsupported by the decoder")
        void unsupported() {
            var info = H264StreamInfo.probe(Fixtures.read(encode("64x64", 1, "yuv444p", List.of())));
            assertEquals(ChromaFormat.YUV444, info.chromaFormat());
            assertFalse(info.decoderSupported());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"yuv422p, 122, 8", "yuv422p10le, 122, 10"})
        @DisplayName("reports 4:2:2 streams as supported by the decoder")
        void chroma422(String pixelFormat, int profile, int depth) {
            var info = H264StreamInfo.probe(Fixtures.read(encode("64x64", 1, pixelFormat, List.of())));
            assertEquals(ChromaFormat.YUV422, info.chromaFormat());
            assertEquals(profile, info.profileIdc());
            assertEquals(depth, info.bitDepthLuma());
            assertEquals(depth, info.bitDepthChroma());
            assertTrue(info.decoderSupported());
        }

        @Test
        @DisplayName("reports 10-bit streams as supported by the decoder")
        void highBitDepth() {
            var info = H264StreamInfo.probe(Fixtures.read(encode("64x64", 1, "yuv420p10le", List.of())));
            assertEquals(110, info.profileIdc());
            assertEquals(10, info.bitDepthLuma());
            assertEquals(10, info.bitDepthChroma());
            assertTrue(info.decoderSupported());
        }

        @Test
        @DisplayName("reports interlaced streams as supported by the decoder")
        void interlaced() {
            var info = H264StreamInfo.probe(Fixtures.read(encode("64x64", 1, "yuv420p",
                    List.of("-x264-params", "interlaced=1:tff=1"))));
            assertFalse(info.frameMbsOnly());
            assertTrue(info.mbAdaptiveFrameField());
            assertTrue(info.decoderSupported());
        }

        /** A 32x32 SPS with picture order count type 2 and no VUI, for any non-High profile. */
        private static byte[] sps(int profile, int constraintFlags, int level) {
            var rbsp = new H264BitWriter().bits(profile, 8).bits(constraintFlags, 8).bits(level, 8).ue(0).ue(0).ue(2)
                    .ue(1).bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing();
            return H264BitWriter.nal(3, 7, rbsp);
        }

        @ParameterizedTest(name = "profile {0}, constraint flags {1}: {2}")
        @CsvSource({"66, 0x00, true", "77, 0x00, true", "88, 0x00, false", "88, 0x10, false", "88, 0x80, true",
                "88, 0x40, true", "88, 0xC0, true"})
        @DisplayName("reports Extended profile streams as supported only when they meet Baseline or Main")
        void extended(int profile, String flags, boolean supported) {
            var info = H264StreamInfo.probe(sps(profile, Integer.decode(flags), 30));
            assertEquals(supported, info.decoderSupported());
        }

        @ParameterizedTest(name = "profile {0}, level_idc {1}, constraint flags {2}")
        @CsvSource({"66, 11, 0x10, LEVEL_1B", "77, 11, 0x10, LEVEL_1B", "88, 11, 0x10, LEVEL_1B",
                "66, 11, 0x00, LEVEL_1_1", "66, 9, 0x00, LEVEL_1B"})
        @DisplayName("recognizes level 1b in both of its encodings")
        void level1b(int profile, int level, String flags, String expected) {
            var info = H264StreamInfo.probe(sps(profile, Integer.decode(flags), level));
            assertEquals(level, info.levelIdc());
            assertEquals(Optional.of(com.github.auties00.smalt.video.h264.encoder.H264Level.valueOf(expected)),
                    info.level());
        }
    }

    @Nested
    @DisplayName("unsupported and oversized streams")
    class Rejections {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "yuv444p, '', h264 4:4:4",
                "yuv422p, -qp:0, h264 lossless",
                "yuv420p, -qp:0, h264 lossless"
        })
        @DisplayName("raises Unsupported with the documented feature name")
        void unsupported(String pixelFormat, String option, String feature) {
            var options = option.isEmpty() ? List.<String>of() : List.of(option.split(":", 2));
            var stream = encode("64x64", 4, pixelFormat, options);
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> H264TestStreams.decode(ANNEX_B, accessUnits(stream), 1));
            assertEquals(feature, failure.format());
        }

        @Test
        @DisplayName("raises LimitExceeded before allocating an oversized picture")
        void limits() {
            var packets = accessUnits(encode("352x288", 1, "yuv420p", List.of()));
            var small = MediaLimits.builder().maxDimension(256).maxPixels(1L << 20).maxFrames(1000).maxChannels(8)
                    .maxBytes(1L << 30).build();
            assertThrows(MediaException.LimitExceeded.class, () -> {
                try (var decoder = new H264Decoder(ANNEX_B, H264DecoderOptions.builder().limits(small).build())) {
                    decoder.decode(packets.getFirst(), frame -> {
                    });
                }
            });
            var tight = MediaLimits.builder().maxDimension(16_384).maxPixels(1L << 24).maxFrames(1000).maxChannels(8)
                    .maxBytes(64 * 1024).build();
            assertThrows(MediaException.LimitExceeded.class, () -> {
                try (var decoder = new H264Decoder(ANNEX_B, H264DecoderOptions.builder().limits(tight).build())) {
                    decoder.decode(packets.getFirst(), frame -> {
                    });
                }
            });
        }

        @Test
        @DisplayName("a hand-built 16384x16384 SPS with 16 references is refused before allocation")
        void hugeSps() {
            var sps = H264BitWriter.sps(100, 1024, 1024, 16);
            var stream = H264TestStreams.annexB(List.of(sps, H264BitWriter.pps(), H264BitWriter.idrSlice(new byte[64])));
            assertThrows(MediaException.LimitExceeded.class, () -> H264TestStreams.decode(ANNEX_B,
                    List.of(new Packet(1, Arrays.copyOf(stream, stream.length), 0, Packet.NO_TIMESTAMP, 0, true)), 1));
        }
    }
}
