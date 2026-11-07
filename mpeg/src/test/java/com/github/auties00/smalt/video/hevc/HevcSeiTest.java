package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.sei.SeiWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SEI messages the HEVC decoder renders or reports: film grain against ffmpeg's renderer on x265 streams
 * with injected film grain characteristics messages (8-bit 4:2:0, the formats ffmpeg renders), its wiring for the
 * deeper and other chroma formats against the renderer applied to the plain decode, and the frame packing, display
 * orientation and picture timing messages.
 */
@DisplayName("HEVC SEI messages")
class HevcSeiTest {
    static final List<List<SeiWriter.Interval>> GRAIN = List.of(
            List.of(new SeiWriter.Interval(0, 120, 60, 8, 6), new SeiWriter.Interval(121, 255, 100, 4, 10)),
            List.of(new SeiWriter.Interval(0, 127, 40, 6, 6), new SeiWriter.Interval(128, 255, 90, 12, 3)),
            List.of(new SeiWriter.Interval(0, 127, 80, 3, 12), new SeiWriter.Interval(128, 255, 30, 10, 10)));

    static Path x265(String size, int frames, String pixelFormat, String parameters) {
        return Fixtures.ffmpeg("hevc", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=25"),
                List.of("-vf", "noise=alls=30:allf=t,format=" + pixelFormat, "-frames:v", Integer.toString(frames),
                        "-c:v", "libx265", "-x265-params", "log-level=error:" + parameters, "-f", "hevc"));
    }

    static Path write(String name, byte[] data) {
        try {
            return Files.write(Scratch.directory("hevc-sei").resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] grainSei(int persistence) {
        return SeiWriter.nal(true, 19, SeiWriter.filmGrain(true, 3, persistence, GRAIN));
    }

    /** Decodes an Annex B stream fed one access unit per packet, keeping copies of the frames' samples. */
    static List<VideoFrame> decode(byte[] stream, HevcDecoderOptions options, List<byte[]> samples) {
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> {
                frames.add(frame);
                samples.add(SeiWriter.raw(frame.picture()));
            };
            for (var unit : HevcTestStreams.accessUnits(HevcTestStreams.nalUnits(stream))) {
                decoder.decode(HevcTestStreams.packet(HevcRandomVectors.annexB(unit)), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static void assertSamples(byte[] reference, List<byte[]> samples, String name) {
        assertFalse(samples.isEmpty(), name + ": no frames");
        var size = samples.getFirst().length;
        assertEquals(reference.length / size, samples.size(), name + ": frame count");
        for (var i = 0; i < samples.size(); i++) {
            assertArrayEquals(Arrays.copyOfRange(reference, i * size, (i + 1) * size), samples.get(i),
                    name + ": frame " + i);
        }
    }

    static byte[] ffmpegDecode(Path stream) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", stream.toString(),
                "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
    }

    record GrainCase(String name, String size, int frames, String parameters) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<GrainCase> grainCases() {
        return Stream.of(
                new GrainCase("B frames and IDR pictures", "176x144", 16, "bframes=3:keyint=8:min-keyint=8"),
                new GrainCase("open GOP with CRA pictures", "176x144", 16, "bframes=2:keyint=6:open-gop=1"),
                new GrainCase("partial chroma blocks", "168x136", 4, "bframes=0"),
                new GrainCase("conformance window", "170x122", 4, "bframes=1"),
                new GrainCase("wavefronts", "256x128", 4, "wpp=1:bframes=0"));
    }

    @Nested
    @DisplayName("film grain")
    class FilmGrain {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.hevc.HevcSeiTest#grainCases")
        @DisplayName("matches ffmpeg's RDD 5 renderer")
        void ffmpeg(GrainCase c) {
            var clean = Fixtures.read(x265(c.size(), c.frames(), "yuv420p", c.parameters()));
            var stream = SeiWriter.insert(clean, true, false, picture -> List.of(grainSei(0)));
            var reference = ffmpegDecode(write("grain-" + c.name().hashCode() + ".hevc", stream));
            for (var threads : new int[]{1, 4}) {
                var samples = new ArrayList<byte[]>();
                decode(stream, HevcDecoderOptions.builder().threads(threads).build(), samples);
                assertSamples(reference, samples, c + " with " + threads + " threads");
            }
        }

        @Test
        @DisplayName("the option is on by default and survives toBuilder, equals, hashCode and toString")
        void option() {
            var defaults = HevcDecoderOptions.defaults();
            assertTrue(defaults.filmGrain());
            var off = defaults.toBuilder().filmGrain(false).build();
            assertFalse(off.filmGrain());
            assertEquals(off, off.toBuilder().build());
            assertEquals(off.hashCode(), off.toBuilder().build().hashCode());
            assertFalse(defaults.equals(off));
            assertTrue(off.toString().contains("filmGrain=false"), off.toString());
        }

        @Test
        @DisplayName("is left out when the option is clear, and decoded picture hashes check the plain samples")
        void disabled() {
            var clean = Fixtures.read(x265("176x144", 6, "yuv420p", "bframes=2:hash=1"));
            var stream = SeiWriter.insert(clean, true, false, picture -> List.of(grainSei(0)));
            var plain = new ArrayList<byte[]>();
            decode(clean, HevcDecoderOptions.defaults(), plain);
            var off = new ArrayList<byte[]>();
            decode(stream, HevcDecoderOptions.builder().filmGrain(false).hashVerification(true).build(), off);
            assertEquals(plain.size(), off.size());
            for (var i = 0; i < plain.size(); i++) {
                assertArrayEquals(plain.get(i), off.get(i));
            }
            var on = new ArrayList<byte[]>();
            decode(stream, HevcDecoderOptions.builder().hashVerification(true).build(), on);
            assertFalse(Arrays.equals(plain.getFirst(), on.getFirst()));
        }

        @Test
        @DisplayName("a message with the persistence flag lasts in output order until a cancel or a new sequence")
        void persistence() {
            var clean = Fixtures.read(x265("176x144", 16, "yuv420p", "bframes=2:keyint=12:min-keyint=12:scenecut=0:open-gop=0"));
            IntFunction<List<byte[]>> persisting = picture -> switch (picture) {
                case 0, 13 -> List.of(grainSei(1));
                case 5 -> List.of(SeiWriter.nal(true, 19, SeiWriter.filmGrainCancel()));
                default -> List.of();
            };
            var samples = new ArrayList<byte[]>();
            decode(SeiWriter.insert(clean, true, false, persisting), HevcDecoderOptions.defaults(), samples);
            var order = displayOrder(clean);
            IntFunction<List<byte[]>> explicit = picture -> {
                var rank = order.indexOf(picture);
                var covered = rank >= order.indexOf(0) && rank < order.indexOf(5) || rank >= order.indexOf(13);
                return covered ? List.of(grainSei(0)) : List.of();
            };
            var reference = ffmpegDecode(write("grain-persist.hevc", SeiWriter.insert(clean, true, false, explicit)));
            assertSamples(reference, samples, "persistence in output order " + order);
        }

        static Stream<String> formats() {
            return Stream.of("yuv420p10le", "yuv422p", "yuv422p10le", "yuv444p", "yuv444p12le", "gray", "gray10le");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("formats")
        @DisplayName("renders the other chroma formats and bit depths on each picture with its picture order count")
        void formats(String pixelFormat) {
            var clean = Fixtures.read(x265("64x48", 4, pixelFormat, "bframes=0"));
            var stream = SeiWriter.insert(clean, true, false, picture -> List.of(grainSei(0)));
            var params = SeiWriter.parseFilmGrain(SeiWriter.filmGrain(true, 3, 0, GRAIN), true);
            var options = HevcDecoderOptions.builder().highBitDepth(true).build();
            var plain = decode(clean, options.toBuilder().filmGrain(false).build(), new ArrayList<>());
            var expected = new ArrayList<byte[]>();
            for (var poc = 0; poc < plain.size(); poc++) {
                // without B frames each picture is output before the next is decoded, and its count is its index
                expected.add(SeiWriter.grained(plain.get(poc).picture(), params, poc));
            }
            var samples = new ArrayList<byte[]>();
            decode(stream, options, samples);
            assertEquals(4, samples.size());
            for (var i = 0; i < samples.size(); i++) {
                assertArrayEquals(expected.get(i), samples.get(i), pixelFormat + " frame " + i);
            }
        }
    }

    /** Returns the pictures of a stream, numbered in decoding order, in their output order according to ffprobe. */
    static List<Integer> displayOrder(byte[] stream) {
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_frames", "-show_entries", "frame=pkt_pos", "-of", "csv=p=0",
                write("order.hevc", stream).toString())), StandardCharsets.UTF_8);
        var positions = probe.lines().filter(line -> !line.isBlank()).map(line -> Long.parseLong(line.split(",")[0].trim()))
                .toList();
        var sorted = positions.stream().sorted().toList();
        var order = new ArrayList<Integer>();
        for (var position : positions) {
            order.add(sorted.indexOf(position));
        }
        return order;
    }

    @Nested
    @DisplayName("display information")
    class Display {
        @Test
        @DisplayName("frame packing and display orientation persist until cancelled")
        void packingAndOrientation() {
            var clean = Fixtures.read(x265("96x64", 6, "yuv420p", "bframes=0"));
            var packing = SeiWriter.nal(true, 45, SeiWriter.framePacking(true, 2, 3, false, 1, true, false, false,
                    false, true, true, new int[]{4, 8, 4, 8}, 1, true));
            var orientation = SeiWriter.nal(true, 47, SeiWriter.displayOrientation(true, false, false, 49152, 1));
            IntFunction<List<byte[]>> messages = picture -> switch (picture) {
                case 1 -> List.of(packing, orientation);
                case 4 -> List.of(SeiWriter.nal(true, 47, SeiWriter.displayOrientationCancel()));
                default -> List.of();
            };
            var frames = decode(SeiWriter.insert(clean, true, false, messages), HevcDecoderOptions.defaults(),
                    new ArrayList<>());
            assertEquals(6, frames.size());
            assertTrue(frames.getFirst().sideData().isEmpty());
            var expected = new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE, FramePacking.Interpretation.FRAME0_LEFT,
                    false, true, false, false, false, true, true, 4, 8, 4, 8, true);
            for (var i = 1; i < 6; i++) {
                var sideData = frames.get(i).sideData();
                assertEquals(expected, sideData.framePacking().orElseThrow(), "frame " + i);
                // a quarter turn clockwise, written as three quarters counterclockwise
                assertEquals(i < 4 ? Orientation.ROTATE_90 : null, sideData.orientation().orElse(null), "frame " + i);
            }
        }

        @Test
        @DisplayName("x265's alternative transfer characteristics last until an IDR picture that does not repeat them")
        void alternativeTransferSequence() {
            var options = "bframes=0:colorprim=bt2020:transfer=bt2020-10:colormatrix=bt2020nc";
            var hlg = Fixtures.read(x265("64x64", 3, "yuv420p", options + ":atc-sei=18"));
            var plain = Fixtures.read(x265("64x64", 3, "yuv420p", options));
            var sequences = new java.io.ByteArrayOutputStream();
            sequences.writeBytes(hlg);
            sequences.writeBytes(plain);
            sequences.writeBytes(hlg);
            var frames = decode(sequences.toByteArray(), HevcDecoderOptions.defaults(), new ArrayList<>());
            assertEquals(9, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var expected = i / 3 == 1 ? TransferCharacteristics.BT2020_10 : TransferCharacteristics.HLG;
                assertEquals(expected, ((YuvPicture) frames.get(i).picture()).color().transfer(), "frame " + i);
            }
        }

        @Test
        @DisplayName("frame pictures report the scan and repetition of their pic_struct")
        void frameTiming() {
            var vector = HevcRandomVectors.named("frame-timing");
            int[] picStructs = {7, 8, 5, 6, 3, 0};
            var units = HevcRandomVectors.withPicStructs(vector.accessUnits(), picStructs);
            var stream = new java.io.ByteArrayOutputStream();
            for (var unit : units) {
                stream.writeBytes(HevcRandomVectors.annexB(unit));
            }
            var frames = decode(stream.toByteArray(), HevcDecoderOptions.defaults(), new ArrayList<>());
            FieldOrder[] orders = {FieldOrder.PROGRESSIVE, FieldOrder.PROGRESSIVE, FieldOrder.TOP_FIRST,
                    FieldOrder.BOTTOM_FIRST, FieldOrder.TOP_FIRST, FieldOrder.PROGRESSIVE};
            int[] repeats = {2, 4, 1, 1, 0, 0};
            assertEquals(6, frames.size());
            for (var i = 0; i < 6; i++) {
                assertEquals(orders[i], frames.get(i).sideData().fieldOrder().orElseThrow(), "frame " + i);
                assertEquals(repeats[i], frames.get(i).sideData().repeatedFields(), "frame " + i);
            }
        }

        @Test
        @DisplayName("frames woven from fields report the parity of their first field")
        void wovenFields() {
            for (var name : List.of("field-ld-bff-10", "field-ra-tff")) {
                var vector = HevcRandomVectors.named(name);
                var frames = new ArrayList<VideoFrame>();
                try (var decoder = new HevcDecoder(HevcTestStreams.track(), HevcDecoderOptions.defaults())) {
                    for (var unit : vector.accessUnits()) {
                        decoder.decode(HevcTestStreams.packet(HevcRandomVectors.annexB(unit)), frames::add);
                    }
                    decoder.flush(frames::add);
                }
                var expected = name.contains("bff") ? FieldOrder.BOTTOM_FIRST : FieldOrder.TOP_FIRST;
                assertEquals(vector.frames(), frames.size());
                for (var frame : frames) {
                    assertEquals(expected, frame.sideData().fieldOrder().orElseThrow(), name);
                }
            }
        }
    }

}
